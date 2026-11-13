package fr.relance.app.core

/** Annulation d'une séance de l'historique. */
object History {
    enum class Effect { ROLLED_BACK, REMOVED_ONLY }

    /**
     * Une séance peut être annulée « pour la refaire » si c'est la dernière séance enregistrée de son programme,
     * et que ce programme est en cours, ou vient de se terminer avec elle. Le programme revient alors exactement
     * à son état d'avant, et la séance réapparaît en tête du planning.
     */
    fun canRollback(state: AppState, s: CompletedSession): Boolean = rollbackTarget(state, s) != null

    private fun rollbackTarget(state: AppState, s: CompletedSession): Program? {
        val before = s.programBefore ?: return null
        val active = state.program
        val current = when {
            active != null -> active.takeIf { it.id == s.programId && it.status == ProgramStatus.ACTIVE }
            else -> state.archived.firstOrNull()?.takeIf {
                it.id == s.programId && (it.status == ProgramStatus.SUCCESS || it.status == ProgramStatus.FINISHED)
            }
        } ?: return null
        return current.takeIf { it.sessionsDone == before.sessionsDone + 1 }
    }

    fun cancel(state: AppState, id: String): Pair<AppState, Effect>? {
        val s = state.sessions.firstOrNull { it.id == id } ?: return null
        val sessions = state.sessions.filter { it.id != id }
        val target = rollbackTarget(state, s)
        val before = s.programBefore
        if (target == null || before == null) return state.copy(sessions = sessions) to Effect.REMOVED_ONLY
        val fromArchive = state.program == null
        return state.copy(
            program = before,
            archived = if (fromArchive) state.archived.drop(1) else state.archived,
            profile = state.profile?.let { p -> s.profileBefore?.restore(p) ?: p },
            sessions = sessions,
        ) to Effect.ROLLED_BACK
    }
}
