package com.winlator.cmod.feature.stores.steam.ludashisteam

import java.util.concurrent.CompletableFuture


interface LudashiAuthenticator {

    /**
     * Steam pushed a "tap to approve" prompt to the user's mobile authenticator.
     * Return a future that completes `true` if the UI should wait for the user
     * to approve out-of-band (always true for our flow — matches IAuthenticator).
     */
    fun acceptDeviceConfirmation(): CompletableFuture<Boolean>

    /**
     * Steam requires a TOTP code from the mobile authenticator.
     * Complete the returned future with the code the user enters.
     * @param previousCodeWasIncorrect true if a prior submission was rejected.
     */
    fun getDeviceCode(previousCodeWasIncorrect: Boolean): CompletableFuture<String>

    
    fun getEmailCode(
        email: String?,
        previousCodeWasIncorrect: Boolean,
    ): CompletableFuture<String>
}
