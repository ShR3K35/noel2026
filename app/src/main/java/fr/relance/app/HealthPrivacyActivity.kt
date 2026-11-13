package fr.relance.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import fr.relance.app.ui.Body
import fr.relance.app.ui.Btn
import fr.relance.app.ui.C
import fr.relance.app.ui.Card
import fr.relance.app.ui.Strong
import fr.relance.app.ui.Title

/** Explication affichée par Santé Connect quand l'utilisateur demande pourquoi Relance veut ces autorisations. */
class HealthPrivacyActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                Column(
                    Modifier.fillMaxSize().background(C.Ground).safeDrawingPadding().verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Title("Relance et Santé Connect")
                    Body("Relance écrit tes séances de tapis dans Santé Connect pour qu'elles apparaissent dans Google Health et tes autres applications de santé.")
                    Card {
                        Strong("Ce que Relance écrit")
                        Body("Pour chaque séance : la séance d'exercice (marche ou course sur tapis), la distance, les calories actives estimées, la vitesse et, si tu portes une ceinture, la fréquence cardiaque.")
                    }
                    Card {
                        Strong("Ce que Relance ne fait pas")
                        Body("Elle ne lit aucune donnée de Santé Connect. Elle n'envoie rien sur Internet : pas de compte, pas de serveur.")
                    }
                    Card {
                        Strong("Revenir en arrière")
                        Body("Tu peux couper l'envoi dans Profil › Santé Connect, retirer les autorisations ou supprimer les données dans les réglages de Santé Connect.")
                    }
                    Btn("Fermer", Modifier.fillMaxWidth()) { finish() }
                }
            }
        }
    }
}
