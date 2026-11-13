import org.vosk.LibVosk;
import org.vosk.LogLevel;
import org.vosk.Model;
import org.vosk.Recognizer;

import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.File;

/**
 * Vérification hors ligne du modèle Vosk français (small-fr-0.22) sur PC, avec la même grammaire que l'app.
 * Usage : java -cp vosk-0.3.45.jar:jna-5.13.0.jar VoskCheck.java <dossier du modèle> <grammaire|libre> fichier.wav…
 * Fichiers WAV 16 kHz mono 16 bits.
 */
public class VoskCheck {
    static final String GRAMMAR = "[\"oui\", \"non\", \"incertain\", \"je ne sais pas\", \"peut-être\", \"bof\", "
        + "\"zéro\", \"un\", \"deux\", \"trois\", \"quatre\", \"cinq\", \"six\", \"sept\", \"huit\", \"neuf\", \"dix\", "
        + "\"arrêter\", \"arrête\", \"stop\", \"ralentir\", \"ralentis\", \"pause\", \"répète\", \"répéter\", \"[unk]\"]";

    public static void main(String[] args) throws Exception {
        LibVosk.setLogLevel(LogLevel.WARNINGS);
        try (Model model = new Model(args[0])) {
            boolean grammar = args[1].equals("grammaire");
            for (int i = 2; i < args.length; i++) {
                try (AudioInputStream ais = AudioSystem.getAudioInputStream(new File(args[i]));
                     Recognizer rec = grammar ? new Recognizer(model, 16000f, GRAMMAR) : new Recognizer(model, 16000f)) {
                    rec.setWords(true);
                    byte[] b = new byte[4096];
                    int n;
                    StringBuilder out = new StringBuilder();
                    while ((n = ais.read(b)) >= 0) if (rec.acceptWaveForm(b, n)) out.append(rec.getResult());
                    out.append(rec.getFinalResult());
                    System.out.println(new File(args[i]).getName() + " => " + out.toString().replaceAll("\\s+", " "));
                }
            }
        }
    }
}
