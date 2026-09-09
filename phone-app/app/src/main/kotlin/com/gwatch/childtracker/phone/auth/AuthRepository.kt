package com.gwatch.childtracker.phone.auth

import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.gwatch.childtracker.phone.R
import kotlinx.coroutines.tasks.await

/**
 * Login con l'account Google del genitore (non l'account "adulto"
 * tecnico del watch). Usa la Google Sign-In API classica invece della
 * piu' recente Credential Manager: meno pezzi in movimento, piu'
 * facile da verificare senza poter compilare in questo ambiente (vedi
 * stessa scelta per OkHttp in watch-app, CONTEXT.md).
 *
 * "default_web_client_id" e' generato automaticamente dal plugin
 * com.google.gms.google-services a partire da google-services.json
 * (Client OAuth di tipo "3" al suo interno) — richiede che il file
 * reale sia presente in app/ (vedi README.md).
 */
class AuthRepository(context: Context) {

    private val auth = FirebaseAuth.getInstance()

    private val googleSignInClient: GoogleSignInClient = run {
        val options = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(context.getString(R.string.default_web_client_id))
            .requestEmail()
            .build()
        GoogleSignIn.getClient(context, options)
    }

    val currentUser: FirebaseUser? get() = auth.currentUser

    fun signInIntent(): Intent = googleSignInClient.signInIntent

    suspend fun handleSignInResult(data: Intent?): FirebaseUser {
        val account = GoogleSignIn.getSignedInAccountFromIntent(data).await()
        val credential = GoogleAuthProvider.getCredential(account.idToken, null)
        val result = auth.signInWithCredential(credential).await()
        return result.user ?: error("Login fallito: utente nullo")
    }

    fun signOut() {
        auth.signOut()
        googleSignInClient.signOut()
    }
}
