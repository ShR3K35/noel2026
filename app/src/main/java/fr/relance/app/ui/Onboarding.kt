package fr.relance.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.relance.app.core.Coach
import fr.relance.app.core.Fmt
import fr.relance.app.core.LastPractice
import fr.relance.app.core.Level
import fr.relance.app.core.Physio
import fr.relance.app.core.Profile
import fr.relance.app.core.ProgramType

private val HEALTH_QUESTIONS = listOf(
    "Un médecin t'a-t-il déjà dit que tu avais un problème cardiaque ?",
    "Ressens-tu des douleurs dans la poitrine, à l'effort ou au repos ?",
    "As-tu des vertiges ou des pertes d'équilibre ?",
    "As-tu un problème articulaire ou osseux que l'effort pourrait aggraver ?",
    "Es-tu enceinte, ou suivie pour une grossesse récente ?",
)
private val DAY_LETTERS = listOf("L", "M", "M", "J", "V", "S", "D")

@Composable
fun DayPicker(days: Set<Int>, onChange: (Set<Int>) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        DAY_LETTERS.forEachIndexed { i, l ->
            val d = i + 1
            Chip(l, d in days, Modifier.weight(1f)) { onChange(if (d in days) days - d else days + d) }
        }
    }
}

@Composable
fun MinutesPicker(minutes: Int, onChange: (Int) -> Unit) {
    val options = listOf(15, 20, 30, 45)
    ChipRow(options.map { "$it min" }, options.indexOf(minutes)) { onChange(options[it]) }
}

/** Le catalogue, avec les durées calculées pour ce profil. */
@Composable
fun CatalogueList(profile: Profile, selected: ProgramType?, onSelect: (ProgramType) -> Unit) {
    val advised = Coach.recommended(profile)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (type in ProgramType.values()) {
            val locked = Coach.lockedReason(type, profile)
            val ladder = Coach.ladderFor(type, profile)
            val isSel = type == selected
            val fg = if (isSel) Color.White else if (locked != null) C.Muted else C.Ink
            Card(
                color = if (isSel) C.Accent else if (locked != null) C.Ground else C.Card, padding = 14.dp,
                onClick = if (locked == null) ({ onSelect(type) }) else null,
            ) {
                if (type == advised && locked == null) Label("Conseillé pour toi", if (isSel) Color.White else C.Accent)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(type.label, color = fg, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Text(locked ?: "${Coach.weeksFor(ladder)} semaines", color = if (isSel) Color.White else C.Muted, fontSize = 13.sp)
                }
                if (locked == null && isSel) {
                    val longest = Coach.longestSessionMinutes(ladder)
                    Small(
                        "Objectif : ${type.validation}." + if (longest > profile.minutesPerSession)
                            " Les séances iront jusqu'à $longest min en fin de programme." else "",
                        Color.White,
                    )
                }
            }
        }
    }
}

@Composable
fun OnboardingScreen(vm: AppViewModel) {
    var step by rememberSaveable { mutableIntStateOf(0) }
    var health by remember { mutableStateOf(List(HEALTH_QUESTIONS.size) { false }) }
    var medication by remember { mutableStateOf(false) }
    var age by remember { mutableIntStateOf(40) }
    var height by remember { mutableIntStateOf(170) }
    var weight by remember { mutableIntStateOf(75) }
    var practice by remember { mutableStateOf(LastPractice.OVER_2Y) }
    var joints by remember { mutableStateOf(setOf<String>()) }
    var vr by remember { mutableStateOf(5.0) }
    var testRpe by remember { mutableIntStateOf(5) }
    var testLater by remember { mutableStateOf(false) }
    var days by remember { mutableStateOf(setOf(1, 3, 6)) }
    var minutes by remember { mutableIntStateOf(30) }
    var type by remember { mutableStateOf<ProgramType?>(null) }

    val draft = Profile(
        age = age, heightCm = height, weightKg = weight.toDouble(), lastPractice = practice,
        healthFlag = health.any { it }, hrMedication = medication, jointIssue = joints.isNotEmpty(),
        vr = vr, testRpe = testRpe, days = days, minutesPerSession = minutes,
    ).let { it.copy(level = Physio.assessLevel(it)) }
    val last = 5

    Column(Modifier.fillMaxSize().background(C.Ground).padding(horizontal = 20.dp, vertical = 24.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Small("Bilan de départ")
            Small("Étape ${step + 1} sur ${last + 1}")
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            repeat(last + 1) {
                Box(Modifier.weight(1f).height(6.dp).clip(RoundedCornerShape(3.dp)).background(if (it <= step) C.Accent else C.Line))
            }
        }
        Spacer(Modifier.height(20.dp))
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            when (step) {
                0 -> {
                    Title("D'abord, ta santé")
                    Body("Réponds honnêtement : ces questions servent à te protéger, pas à te noter.", C.Muted)
                    HEALTH_QUESTIONS.forEachIndexed { i, q ->
                        Card(padding = 14.dp) {
                            Body(q)
                            ChipRow(listOf("Non", "Oui"), if (health[i]) 1 else 0) { c ->
                                health = health.toMutableList().also { it[i] = c == 1 }
                            }
                        }
                    }
                    Card(padding = 14.dp) {
                        Body("Prends-tu un traitement qui modifie le rythme cardiaque (bêtabloquant par exemple) ?")
                        ChipRow(listOf("Non", "Oui"), if (medication) 1 else 0) { medication = it == 1 }
                    }
                    if (health.any { it }) Card(color = C.Soft) {
                        Strong("Avis médical conseillé avant de commencer")
                        Body("Parles-en à ton médecin. En attendant, l'app te propose uniquement le programme de marche « Santé et forme ».")
                    }
                }
                1 -> {
                    Title("Parle-moi de toi")
                    Strong("Âge")
                    Stepper("$age", "ans", { age = (age - 1).coerceAtLeast(14) }, { age = (age + 1).coerceAtMost(90) })
                    Strong("Taille")
                    Stepper("$height", "cm", { height = (height - 1).coerceAtLeast(130) }, { height = (height + 1).coerceAtMost(215) })
                    Strong("Poids")
                    Stepper("$weight", "kg", { weight = (weight - 1).coerceAtLeast(35) }, { weight = (weight + 1).coerceAtMost(200) })
                    Small("Le poids sert à estimer les calories et à doser l'impact. Il reste sur ton téléphone.")
                    Strong("Ta dernière pratique sportive régulière")
                    LastPractice.values().forEach { lp -> Chip(lp.label, practice == lp, Modifier.fillMaxWidth()) { practice = lp } }
                }
                2 -> {
                    Title("Des zones sensibles ?")
                    Body("Si une zone te gêne, je limite la course et je mise davantage sur la marche en côte, qui fait travailler le cœur sans les chocs.", C.Muted)
                    listOf("Genoux", "Dos", "Chevilles ou pieds", "Hanches").forEach { z ->
                        Chip(z, z in joints, Modifier.fillMaxWidth()) { joints = if (z in joints) joints - z else joints + z }
                    }
                    Chip("Aucune", joints.isEmpty(), Modifier.fillMaxWidth()) { joints = emptySet() }
                }
                3 -> {
                    Title("Le test de marche")
                    Body("Sur ton tapis : 3 min de marche lente, puis 6 min en montant la vitesse jusqu'à l'allure la plus vive où tu peux encore parler. Note cette vitesse.", C.Muted)
                    Card {
                        Strong("Vitesse tenue pendant le test")
                        Stepper(Fmt.kmh(vr), "km/h", { vr = Physio.round1((vr - 0.1).coerceAtLeast(3.0)) }, { vr = Physio.round1((vr + 0.1).coerceAtMost(9.0)) })
                    }
                    Card {
                        Strong("Effort ressenti pendant ces 6 min")
                        Small("1 très facile · 10 maximal")
                        ChipRow((1..5).map { "$it" }, testRpe - 1) { testRpe = it + 1 }
                        ChipRow((6..10).map { "$it" }, testRpe - 6) { testRpe = it + 6 }
                    }
                    Chip(
                        if (testLater) "Test prévu en première séance" else "Je n'ai pas encore fait le test",
                        testLater, Modifier.fillMaxWidth(),
                    ) { testLater = !testLater }
                    if (testLater) Small("Ta première séance sera ce test guidé de 12 min. D'ici là, je pars de la valeur ci-dessus.")
                }
                4 -> {
                    Title("Quand peux-tu t'entraîner ?")
                    Body("Coche les jours réalistes, pas les jours idéaux. Deux séances tenues valent mieux que quatre prévues.", C.Muted)
                    Strong("Jours possibles")
                    DayPicker(days) { days = it }
                    Strong("Temps disponible par séance")
                    MinutesPicker(minutes) { minutes = it }
                    Small("Tu pourras raccourcir une séance le jour même.")
                    val n = Coach.trainingDays(draft).size
                    Card(color = C.Soft) {
                        Strong(if (days.isEmpty()) "Choisis au moins un jour" else "$n séance${if (n > 1) "s" else ""} par semaine · $minutes min")
                        Body(
                            when {
                                days.isEmpty() -> "Sans jour coché, impossible de planifier."
                                days.size > 4 -> "En reprise, 4 séances par semaine suffisent : je retiens 4 jours bien répartis."
                                n == 1 -> "Une séance par semaine entretient. Deux, c'est là que les progrès commencent."
                                else -> "Bon rythme pour reprendre : assez pour progresser, assez de repos pour assimiler."
                            }
                        )
                    }
                }
                else -> {
                    Title("Choisis ton objectif")
                    Small("Niveau de départ : ${draft.level.label}. Durées calculées pour ${Coach.trainingDays(draft).size} séances par semaine. Un seul programme à la fois.")
                    CatalogueList(draft, type ?: Coach.recommended(draft)) { type = it }
                }
            }
            Spacer(Modifier.height(4.dp))
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (step > 0) Btn("Retour", Modifier.width(110.dp), filled = false) { step-- }
            Btn(
                if (step == last) "Voir mon programme" else "Continuer", Modifier.weight(1f),
                enabled = !(step == 4 && days.isEmpty()),
            ) {
                if (step < last) step++ else vm.completeOnboarding(draft, type ?: Coach.recommended(draft), testLater)
            }
        }
    }
}

/** Catalogue ouvert depuis l'onglet Programmes, quand aucun programme n'est en cours. */
@Composable
fun CatalogueScreen(vm: AppViewModel) {
    val profile = vm.state.profile ?: return
    var type by remember { mutableStateOf(Coach.recommended(profile)) }
    Column(Modifier.fillMaxSize().background(C.Ground).padding(horizontal = 20.dp, vertical = 24.dp)) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Title("Choisis ton objectif")
            Small("Niveau : ${profile.level.label} · vitesse de référence ${Fmt.kmh(profile.vr)} km/h. Durées calculées pour ${Coach.trainingDays(profile).size} séances par semaine.")
            CatalogueList(profile, type) { type = it }
            if (profile.level == Level.N1) Small("Tu démarres par la marche : c'est la base la plus solide.")
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Btn("Retour", Modifier.width(110.dp), filled = false) { vm.back() }
            Btn("Démarrer ce programme", Modifier.weight(1f), enabled = Coach.lockedReason(type, profile) == null) { vm.startProgram(type) }
        }
    }
}
