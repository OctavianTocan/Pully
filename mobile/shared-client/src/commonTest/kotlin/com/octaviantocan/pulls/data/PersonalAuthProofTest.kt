package com.octaviantocan.pulls.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PersonalAuthProofTest {
    @Test
    fun matchesTheServerRfc7636AsciiProofVector() {
        val encoded = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
        val proof = PersonalAuthProof.fromEntropy(proofBase64.decode(encoded))
        assertEquals(encoded, proof.secret)
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", proof.challenge)
        assertFalse(proof.toString().contains(encoded))
    }

    @Test
    fun nativeEntropyProducesIndependentCanonicalProofs() {
        val first = PersonalAuthProof.create()
        val second = PersonalAuthProof.create()
        assertTrue(canonicalPersonalSecret(first.secret))
        assertTrue(canonicalPersonalSecret(first.challenge))
        assertTrue(canonicalPersonalSecret(second.secret))
        assertTrue(first.secret != second.secret)
        assertTrue(first.challenge != second.challenge)
        // Changing unused padding bits must not create a second representation of a proof.
        assertFalse(canonicalPersonalSecret("a".repeat(43)))
        assertFalse(canonicalPersonalSecret(first.secret + "="))
    }
}
