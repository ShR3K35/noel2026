# Vérification hors ligne de Vosk

Reconnaît des fichiers WAV (16 kHz, mono, 16 bits) avec le modèle `vosk-model-small-fr-0.22` et la grammaire de l'app (`VoiceCommands.GRAMMAR`), sans réseau.

```bash
curl -LO https://repo1.maven.org/maven2/com/alphacephei/vosk/0.3.45/vosk-0.3.45.jar
curl -LO https://repo1.maven.org/maven2/net/java/dev/jna/jna/5.13.0/jna-5.13.0.jar
curl -LO https://alphacephei.com/vosk/models/vosk-model-small-fr-0.22.zip && unzip vosk-model-small-fr-0.22.zip
# Sous Linux, « unshare -n » coupe le réseau pour prouver le fonctionnement hors ligne.
unshare -n java -cp vosk-0.3.45.jar:jna-5.13.0.jar VoskCheck.java vosk-model-small-fr-0.22 grammaire oui.wav trois.wav
```

`libre` à la place de `grammaire` reconnaît tout le vocabulaire, avec l'horodatage des mots.
