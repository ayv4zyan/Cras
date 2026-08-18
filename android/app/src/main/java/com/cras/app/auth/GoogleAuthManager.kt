package com.cras.app.auth

import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import java.security.MessageDigest
import java.util.UUID

class GoogleAuthManager(
    private val context: Context,
    private val serverClientId: String = "cras-google-oauth-client-id"
) {
    private val credentialManager = CredentialManager.create(context)

    suspend fun getGoogleIdToken(): GoogleSignInResult {
        return try {
            val rawNonce = UUID.randomUUID().toString()
            val md = MessageDigest.getInstance("SHA-256")
            val digest = md.digest(rawNonce.toByteArray())
            val hashedNonce = digest.fold("") { str, it -> str + "%02x".format(it) }

            val googleIdOption = GetGoogleIdOption.Builder()
                .setFilterByAuthorizedAccounts(false)
                .setServerClientId(serverClientId)
                .setAutoSelectEnabled(false)
                .setNonce(hashedNonce)
                .build()

            val request = GetCredentialRequest.Builder()
                .addCredentialOption(googleIdOption)
                .build()

            val result = credentialManager.getCredential(
                request = request,
                context = context
            )

            val credential = result.credential
            if (credential is CustomCredential &&
                credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
            ) {
                val googleIdTokenCredential = GoogleIdTokenCredential.createFrom(credential.data)
                GoogleSignInResult.Success(
                    idToken = googleIdTokenCredential.idToken,
                    nonce = rawNonce
                )
            } else {
                GoogleSignInResult.Error("Unsupported credential type: ${credential.type}")
            }
        } catch (e: GetCredentialCancellationException) {
            GoogleSignInResult.Cancelled
        } catch (e: GetCredentialException) {
            GoogleSignInResult.Error(e.message ?: "Google sign in failed")
        } catch (e: Exception) {
            GoogleSignInResult.Error(e.message ?: "Unexpected error during Google sign in")
        }
    }
}

sealed interface GoogleSignInResult {
    data class Success(val idToken: String, val nonce: String?) : GoogleSignInResult
    object Cancelled : GoogleSignInResult
    data class Error(val message: String) : GoogleSignInResult
}
