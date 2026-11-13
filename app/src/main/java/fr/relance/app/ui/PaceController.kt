package fr.relance.app.ui

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import fr.relance.app.core.Acoustic
import fr.relance.app.core.AcousticComparison
import fr.relance.app.core.AcousticFeatures
import fr.relance.app.core.CalibrationRecord
import fr.relance.app.core.Fmt
import fr.relance.app.core.Heard
import fr.relance.app.core.Observation
import fr.relance.app.core.PaceProtocol
import fr.relance.app.core.PaceTest
import fr.relance.app.core.PaceTestState
import fr.relance.app.core.PauseReason
import fr.relance.app.core.Phase
import fr.relance.app.core.Question
import fr.relance.app.core.SegKind
import fr.relance.app.core.Speech
import fr.relance.app.core.Symptom
import fr.relance.app.core.VoiceBaseline
import fr.relance.app.core.VoiceCommands
import fr.relance.app.voice.AnswerRecognizer
import fr.relance.app.voice.AudioCapture
import fr.relance.app.voice.Diagnostics
import fr.relance.app.voice.MicState
import fr.relance.app.voice.ReadingBuffer
import fr.relance.app.voice.Speaker
import fr.relance.app.voice.SpeechModelStore
import fr.relance.app.voice.VoskAnswerRecognizer
import java.io.File
import java.util.concurrent.Executors

/** Réponses en cours de saisie pour un contrôle (boutons ou voix). */
class CheckFlow(val key: String, val late: Boolean) {
    var reading by mutableStateOf(true)
    var speech by mutableStateOf<Speech?>(null)
    var rpe by mutableStateOf<Int?>(null)
    var movement by mutableStateOf<Boolean?>(null)
    var acoustic by mutableStateOf<AcousticComparison?>(null)
    /** Question de confort supplémentaire après une lecture qui semblait dégradée. */
    var extra by mutableStateOf(false)
    var extraAsked = false
    var viaVoice = false
    var heard by mutableStateOf("")
    val complete: Boolean get() = speech != null && rpe != null && movement != null
}

/**
 * Déroulé d'un test de calibration dans l'app : horloge monotone, voix (synthèse et reconnaissance locales),
 * analyse des lectures, sauvegarde. Le tapis reste réglé à la main ; l'app ne fait que recommander.
 */
class PaceController(
    private val ctx: Context,
    private val speaker: Speaker,
    private val persist: (PaceTestState?) -> Unit,
    private val voiceOn: () -> Boolean,
    private val keepDiagnostics: () -> Boolean,
) {
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    val models = SpeechModelStore(File(ctx.filesDir, "models"))
    val diagnostics = Diagnostics(ctx.noBackupFilesDir)
    private val capture = AudioCapture(ctx)
    private val android = fr.relance.app.voice.AndroidSpeech(ctx)

    /** Moteur de reconnaissance retenu : celui d'Android sur l'appareil (Pixel…), sinon Vosk. */
    enum class Asr(val label: String) {
        NONE(""),
        ANDROID("Reconnaissance vocale d'Android, sur le téléphone (sans Internet)"),
        VOSK("Reconnaissance vocale Vosk, sur le téléphone (sans Internet)"),
    }
    var asr by mutableStateOf(Asr.NONE)
        private set
    /** Le français hors ligne d'Android peut être installé par le système. */
    var androidDownloadable by mutableStateOf(false)
        private set

    var engine by mutableStateOf<PaceTest?>(null)
        private set
    /** Incrémenté à chaque changement : l'écran se redessine. */
    var version by mutableIntStateOf(0)
        private set
    var mic by mutableStateOf(MicState.OFF)
        private set
    var micMessage by mutableStateOf("")
        private set
    var downloadProgress by mutableFloatStateOf(0f)
        private set
    var flow by mutableStateOf<CheckFlow?>(null)
        private set
    var baseline by mutableStateOf<VoiceBaseline?>(null)
        private set
    /** Lectures de départ déjà faites (0, 1, 2). */
    var baselineReadings by mutableStateOf<List<AcousticFeatures>>(emptyList())
        private set
    var ambientDb by mutableStateOf<Double?>(null)
        private set
    var prepBusy by mutableStateOf("")
        private set

    private var recognizer: AnswerRecognizer? = null
    private enum class Mode { IDLE, AMBIENT, READING, LISTEN }
    @Volatile private var mode = Mode.IDLE
    private val buffer = ReadingBuffer()
    private var chunks = 0
    private var question: Question? = null
    private var retries = 0
    private var onReading: ((ShortArray) -> Unit)? = null
    private var lastPersist = 0L
    private var lastPhase: Phase? = null
    private var lastSegments = 0
    private var cancelDownload = false
    /** Appelé une fois, quand le test se termine. */
    var onFinished: (() -> Unit)? = null

    fun now(): Int {
        val e = engine ?: return 0
        if (e.s.phase == Phase.READY) return 0
        return ((SystemClock.elapsedRealtime() - e.s.monoStartMillis) / 1000).toInt()
    }

    // ---------- Préparation du micro (avant le mouvement) ----------

    val voiceAvailable: Boolean get() = mic in setOf(MicState.READY, MicState.SPEAKING, MicState.READING, MicState.LISTENING)

    fun permissionDenied() {
        mic = MicState.NO_PERMISSION
        micMessage = "Sans micro, le test se fait entièrement avec les boutons."
    }

    /** Prépare la reconnaissance : permission accordée, puis moteur d'Android sur l'appareil, sinon Vosk. */
    fun prepareVoice() {
        if (!capture.permitted()) {
            permissionDenied(); return
        }
        if (asr == Asr.ANDROID) {
            useAndroid(); return
        }
        if (fr.relance.app.voice.AndroidSpeech.available(ctx) && asr != Asr.VOSK) {
            mic = MicState.LOADING
            android.checkSupport { support ->
                main.post {
                    when (support) {
                        fr.relance.app.voice.AndroidSpeech.Support.INSTALLED, fr.relance.app.voice.AndroidSpeech.Support.UNKNOWN -> useAndroid()
                        fr.relance.app.voice.AndroidSpeech.Support.DOWNLOADABLE -> {
                            androidDownloadable = true
                            mic = MicState.NO_MODEL
                            micMessage = "Le français hors ligne d'Android n'est pas encore installé. Installe-le (gratuit, géré par Android), puis touche « Vérifier » ; ou utilise Vosk."
                        }
                        fr.relance.app.voice.AndroidSpeech.Support.UNSUPPORTED -> prepareVosk()
                    }
                }
            }
            return
        }
        prepareVosk()
    }

    /** Installation du français hors ligne par Android. */
    fun installAndroidFrench() = android.download()

    /** Choisir Vosk plutôt que la reconnaissance d'Android. */
    fun chooseVosk() {
        asr = Asr.VOSK
        androidDownloadable = false
        prepareVosk()
    }

    private fun useAndroid() {
        asr = Asr.ANDROID
        androidDownloadable = false
        mic = MicState.READY
        micMessage = Asr.ANDROID.label + ". Réponds à voix haute pendant les contrôles ; « pause », « ralentir » et « arrêter » marchent pendant les questions."
    }

    private fun prepareVosk() {
        asr = Asr.VOSK
        if (!models.ready) {
            mic = MicState.NO_MODEL
            micMessage = "Le modèle vocal français (41 Mo, gratuit, licence Apache 2.0) doit être téléchargé une fois. Il fonctionne ensuite sans Internet."
            return
        }
        if (recognizer != null) {
            startCapture(); return
        }
        mic = MicState.LOADING
        io.execute {
            val r = try {
                VoskAnswerRecognizer(models.modelDir)
            } catch (e: Throwable) {
                null
            }
            main.post {
                if (r == null) {
                    mic = MicState.ERROR
                    micMessage = "Le modèle vocal n'a pas pu être chargé. Tu peux le retélécharger, ou continuer avec les boutons."
                } else {
                    recognizer = r
                    startCapture()
                }
            }
        }
    }

    fun downloadModel() {
        if (mic == MicState.DOWNLOADING) return
        mic = MicState.DOWNLOADING
        cancelDownload = false
        downloadProgress = 0f
        io.execute {
            val err = models.download({ p -> main.post { downloadProgress = p } }, { cancelDownload })
            main.post {
                if (err == null) prepareVoice() else {
                    mic = MicState.NO_MODEL
                    micMessage = err
                }
            }
        }
    }

    fun cancelDownload() {
        cancelDownload = true
    }

    private fun startCapture() {
        val err = capture.start(object : AudioCapture.Sink {
            override fun onAudio(buf: ShortArray, n: Int) = audio(buf, n)
            override fun onError(message: String) {
                main.post {
                    mic = MicState.ERROR
                    micMessage = "$message Continue avec les boutons."
                    mode = Mode.IDLE
                }
            }
        })
        if (err != null) {
            mic = MicState.ERROR
            micMessage = "$err Continue avec les boutons."
        } else {
            if (asr == Asr.VOSK) {
                mic = MicState.READY
                micMessage = capture.device.replaceFirstChar { it.uppercase() } + " utilisé. Garde le téléphone au même endroit pendant tout le test."
            }
        }
    }

    /**
     * Avec la reconnaissance d'Android, le micro de l'app ne tourne que pendant une lecture : les deux ne se disputent
     * jamais le micro.
     */
    private fun ensureCapture(): Boolean {
        if (asr != Asr.ANDROID) return true
        android.cancel()
        startCapture()
        return mic != MicState.ERROR
    }

    fun stopVoice() {
        mode = Mode.IDLE
        android.cancel()
        capture.stop()
        if (mic != MicState.NO_PERMISSION && mic != MicState.NO_MODEL) mic = MicState.OFF
    }

    /** Trois secondes de silence : bruit ambiant. */
    fun recordAmbient() {
        if (!voiceAvailable) return
        if (!ensureCapture()) return
        prepBusy = "Reste silencieux trois secondes…"
        buffer.clear()
        onReading = { pcm ->
            ambientDb = Acoustic.noiseDb(pcm)
            prepBusy = ""
        }
        mode = Mode.AMBIENT
    }

    /** Une lecture de départ : le même passage, lu naturellement, respiration libre. */
    fun recordBaselineReading() {
        if (!voiceAvailable) return
        speaker.say("Lis le texte à voix haute, naturellement.") {
            if (!ensureCapture()) return@say
            prepBusy = "Lis le texte à voix haute…"
            mic = MicState.READING
            buffer.clear(); chunks = 0
            onReading = { pcm ->
                val f = Acoustic.analyze(pcm, ambientDb = ambientDb)
                if (keepDiagnostics()) diagnostics.save("depart-${System.currentTimeMillis()}", pcm)
                baselineReadings = (baselineReadings + f).takeLast(2)
                if (baselineReadings.size == 2) baseline = VoiceBaseline(baselineReadings, ambientDb ?: -90.0, device = capture.device)
                prepBusy = ""
                mic = MicState.READY
            }
            mode = Mode.READING
        }
    }

    fun resetBaseline() {
        baselineReadings = emptyList(); baseline = null
    }

    // ---------- Audio (fil du micro) ----------

    private fun audio(buf: ShortArray, n: Int) {
        val m = mode
        if (m == Mode.IDLE) return
        if (speaker.muted(SystemClock.elapsedRealtime())) return
        when (m) {
            Mode.AMBIENT -> {
                buffer.add(buf, n)
                if (buffer.seconds >= 3) finishReading()
            }
            Mode.READING -> {
                buffer.add(buf, n)
                if (++chunks % 5 == 0 && (buffer.full() || buffer.ended(ambientDb ?: -90.0))) finishReading()
            }
            Mode.LISTEN -> {
                val text = try {
                    recognizer?.accept(buf, n)
                } catch (e: Throwable) {
                    null
                }
                if (text != null) main.post { heard(text) }
            }
            Mode.IDLE -> Unit
        }
    }

    private fun finishReading() {
        mode = Mode.IDLE
        val pcm = buffer.pcm()
        buffer.clear()
        val cb = onReading
        onReading = null
        // L'audio n'est gardé qu'en mémoire, le temps de l'analyse.
        main.post {
            if (asr == Asr.ANDROID) {
                capture.stop()
                if (mic == MicState.READING) mic = MicState.READY
            }
            cb?.invoke(pcm)
        }
    }

    // ---------- Test ----------

    fun begin(state: PaceTestState, declaredKmh: Double) {
        val e = PaceTest(state.copy(voiceUsed = voiceAvailable, voiceBaseline = baseline?.takeIf { it.valid }))
        e.start(declaredKmh, SystemClock.elapsedRealtime(), System.currentTimeMillis())
        engine = e
        lastPhase = Phase.RUNNING
        lastSegments = 1
        flow = null
        save(force = true)
        if (voiceAvailable) listen(null)
        announce("Échauffement, cinq minutes de marche facile à ${speak(declaredKmh)}.")
    }

    /** Après une relance de l'app : le test reste en pause jusqu'à confirmation. */
    fun restore(state: PaceTestState) {
        val e = PaceTest(state)
        val now = ((SystemClock.elapsedRealtime() - state.monoStartMillis) / 1000).toInt()
        e.restored(now)
        engine = e
        lastPhase = e.s.phase
        lastSegments = e.s.segments.size
        save(force = true)
    }

    fun tick() {
        val e = engine ?: return
        if (e.s.phase == Phase.FINISHED) return
        e.tick(now())
        afterChange()
    }

    private fun afterChange() {
        val e = engine ?: return
        val s = e.s
        val ref = e.openCheck()
        val key = ref?.let { "${it.segIndex}:${it.check}" }
        if (key != flow?.key) {
            if (key == null) {
                flow = null
                if (voiceAvailable) listen(null)
            } else startCheck(CheckFlow(key, ref.late))
        }
        if (s.phase != lastPhase || s.segments.size != lastSegments) {
            when {
                s.phase == Phase.AWAIT_SPEED -> s.pending?.let { p ->
                    val what = when (p.kind) {
                        SegKind.STEP -> "Palier ${p.n}"
                        SegKind.REP -> "Répétition de trot ${p.n}"
                        else -> "Allure constante, dix minutes"
                    }
                    announce(if (p.proposedKmh > 0) "$what. Règle le tapis sur ${speak(p.proposedKmh)}, puis confirme." else "$what. Règle ton trot lent naturel, puis confirme la vitesse.")
                }
                s.phase == Phase.RUNNING && s.current?.kind == SegKind.COOLDOWN && s.segments.size != lastSegments ->
                    announce("Retour au calme. Ralentis à ${speak(s.current!!.proposedKmh)}.")
                s.phase == Phase.RUNNING && s.current?.kind == SegKind.RECOVERY && s.segments.size != lastSegments ->
                    announce("Marche. Reviens à ${speak(s.current!!.proposedKmh)}.")
                s.phase == Phase.FINISHED -> {
                    announce(if (s.symptom != null) "Arrête le tapis." else "Test terminé. Tu peux arrêter le tapis.")
                    flow = null
                    stopVoice()
                    lastPhase = s.phase
                    save(force = true)
                    version++
                    onFinished?.invoke()
                    return
                }
            }
            lastPhase = s.phase
            lastSegments = s.segments.size
            save(force = true)
        } else save(force = false)
        version++
    }

    private fun save(force: Boolean) {
        val t = SystemClock.elapsedRealtime()
        if (force || t - lastPersist > 10_000) {
            lastPersist = t
            persist(engine?.s)
        }
    }

    private fun speak(kmh: Double) = "${Fmt.kmh(kmh).replace(",", " virgule ")} kilomètres heure"

    private fun announce(text: String) {
        if (!voiceOn()) return
        speaker.say(text) { if (voiceAvailable && mode != Mode.READING) listen(question) }
    }

    // ---------- Contrôles ----------

    private fun startCheck(f: CheckFlow) {
        flow = f
        question = null
        val b = engine?.s?.voiceBaseline
        if (voiceAvailable && b != null && !f.late) {
            speaker.say("Contrôle. Lis le texte à voix haute, naturellement.") {
                if (!ensureCapture()) {
                    f.reading = true; return@say
                }
                mic = MicState.READING
                buffer.clear(); chunks = 0
                onReading = { pcm ->
                    val feat = Acoustic.analyze(pcm, ambientDb = b.ambientDb)
                    if (keepDiagnostics()) diagnostics.save("controle-${System.currentTimeMillis()}", pcm)
                    if (flow === f) {
                        f.acoustic = Acoustic.compare(b, feat)
                        f.reading = false
                        askNext()
                    }
                }
                mode = Mode.READING
            }
        } else if (voiceAvailable) {
            speaker.say(if (f.late) "Réponds pour la répétition que tu viens de finir." else "Contrôle. Lis le texte à voix haute, puis réponds.") { f.reading = false; askNext() }
        }
        version++
    }

    /** L'utilisateur a lu le texte (sans micro, ou en passant l'enregistrement). */
    fun readingDone() {
        val f = flow ?: return
        if (mode == Mode.READING) {
            mode = Mode.IDLE; buffer.clear(); onReading = null
        }
        f.reading = false
        if (voiceAvailable) askNext() else version++
    }

    private fun askNext() {
        val f = flow ?: return
        val q = when {
            f.extra && !f.extraAsked -> Question.EXTRA
            f.speech == null -> Question.SPEECH
            f.rpe == null -> Question.RPE
            f.movement == null -> Question.MOVEMENT
            else -> null
        }
        if (q == null) {
            submitIfComplete(); return
        }
        if (!voiceAvailable) return
        question = q
        retries = 0
        mic = MicState.SPEAKING
        speaker.say(q.prompt) { listen(q) }
    }

    private fun listen(q: Question?) {
        if (!voiceAvailable && mic != MicState.SPEAKING) return
        question = q
        if (asr == Asr.ANDROID) {
            mode = Mode.IDLE
            if (q == null) {
                android.cancel(); mic = MicState.READY; return
            }
            mic = MicState.LISTENING
            android.listen(
                { hyps -> main.post { if (question == q) heard(VoiceCommands.best(hyps, q)) } },
                { err ->
                    main.post {
                        if (err in fr.relance.app.voice.AndroidSpeech.LANGUAGE_ERRORS) {
                            mic = MicState.ERROR
                            micMessage = "Le français n'est pas disponible hors ligne sur ce téléphone : réponds avec les boutons, ou choisis Vosk dans la préparation."
                        } else if (question == q) heard("")
                    }
                },
            )
            return
        }
        try {
            recognizer?.reset()
        } catch (e: Throwable) {
            // Ignoré : la prochaine réponse sera reconnue normalement.
        }
        mode = Mode.LISTEN
        mic = if (q != null) MicState.LISTENING else MicState.READY
    }

    private fun heard(text: String) {
        val f = flow
        val q = question
        val h = VoiceCommands.parse(text, q)
        f?.heard = text
        when (h) {
            Heard.Stop -> stop()
            Heard.Pause -> pause(PauseReason.USER)
            Heard.SlowDown -> slowDown()
            Heard.Repeat -> q?.let { speaker.say(it.prompt) { listen(it) } }
            is Heard.Comfort -> if (f != null) {
                f.viaVoice = true
                if (q == Question.EXTRA) {
                    f.extraAsked = true; f.speech = h.value
                } else f.speech = h.value
                askNext()
            }
            is Heard.Effort -> if (f != null) {
                f.viaVoice = true; f.rpe = h.value; askNext()
            }
            is Heard.YesNo -> if (f != null) {
                f.viaVoice = true; f.movement = h.value; askNext()
            }
            is Heard.Ambiguous, Heard.Nothing -> if (q != null && f != null) {
                if (retries++ < 1) speaker.say(q.retry) { listen(q) } else {
                    // Après une confirmation ciblée sans succès : on laisse les boutons.
                    listen(null)
                }
            }
        }
        version++
    }

    fun setSpeech(v: Speech) {
        val f = flow ?: return
        if (f.extra && !f.extraAsked) f.extraAsked = true
        f.speech = v; submitIfComplete(); version++
    }

    fun setRpe(v: Int) {
        flow?.rpe = v; submitIfComplete(); version++
    }

    fun setMovement(v: Boolean) {
        flow?.movement = v; submitIfComplete(); version++
    }

    private fun submitIfComplete() {
        val f = flow ?: return
        if (!f.complete) {
            if (voiceAvailable && question == null) askNext()
            return
        }
        // Lecture qui semblait moins fluide : une question de confort de plus, si le temps le permet. Jamais une décision.
        val e = engine ?: return
        if (f.acoustic?.changed == true && f.speech == Speech.YES && !f.extra && e.s.remaining > 30) {
            f.extra = true
            if (voiceAvailable) askNext()
            version++
            return
        }
        if (f.extra && !f.extraAsked) return
        submit()
    }

    /** Valide le contrôle tel quel : une réponse manquante donne un résultat indéterminé (pas de progression). */
    fun submit() {
        val f = flow ?: return
        val e = engine ?: return
        e.answer(
            Observation(
                0, speech = f.speech, rpe = f.rpe, movementOk = f.movement, viaVoice = f.viaVoice,
                extraQuestion = f.extraAsked, acoustic = f.acoustic,
            ),
            now(),
        )
        flow = null
        question = null
        afterChange()
    }

    // ---------- Actions ----------

    fun confirmSpeed(kmh: Double): Boolean = engine?.confirmSpeed(kmh, now()).also { afterChange() } ?: false
    fun correctSpeed(kmh: Double): Boolean = engine?.correctSpeed(kmh, now()).also { afterChange() } ?: false
    fun slowDown(): Boolean = engine?.slowDown(now()).also { afterChange() } ?: false
    fun endProgression(reason: String) = engine?.endProgression(reason, now()).also { afterChange() }
    fun extendRecovery(): Boolean = engine?.extendRecovery(now()).also { afterChange() } ?: false

    fun pause(reason: PauseReason) {
        engine?.pause(reason, now())
        mode = Mode.IDLE
        afterChange()
    }

    fun resume() {
        engine?.resume(now())
        if (voiceAvailable) listen(null)
        afterChange()
    }

    fun stop() {
        engine?.stop(now()); afterChange()
    }

    fun symptom(s: Symptom) {
        engine?.symptom(s, now()); afterChange()
    }

    fun result(): CalibrationRecord? = engine?.result()

    /** Fin du test (résultat enregistré ou abandonné) : plus rien en mémoire, ni audio ni état. */
    fun close() {
        stopVoice()
        engine = null
        flow = null
        baseline = null
        baselineReadings = emptyList()
        ambientDb = null
        persist(null)
    }

    fun shutdown() {
        capture.stop()
        android.destroy()
        try {
            recognizer?.close()
        } catch (e: Throwable) {
            // Rien à libérer.
        }
        io.shutdown()
    }

    val budgetLeft: Int get() = engine?.let { PaceProtocol.BUDGET_SEC - maxOf(it.s.now, now()) }?.coerceAtLeast(0) ?: PaceProtocol.BUDGET_SEC
}
