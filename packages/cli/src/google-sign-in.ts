import type { JWTVerifyGetKey } from 'jose'
import { createRemoteJWKSet, jwtVerify } from 'jose'

/**
 * Google sign-in: the phone signs in to Google with Credential Manager and sends the ID token Google gave it; this
 * checks the token against Google's published keys with `jose` and answers the email it was given for, which signs in
 * to that email's account, made the first time. Only `GOOGLE_ALLOWED` emails may. The token's audience is the server's
 * web client ID (`PULLY_GOOGLE_CLIENT_ID`), the one the phone asked Google for, so a token minted for any other app is
 * refused.
 */

/**
 * Who may sign in with Google: Tavi's address and anyone at his domain (Tavi, 16 Oct 16:52). Everyone else is refused
 * before an account is made.
 */
export const GOOGLE_ALLOWED: readonly string[] = ['tocanoctavian@gmail.com', '@octaviantocan.com']

/** Whether `email` is one `GOOGLE_ALLOWED` names, by the whole address or by an `@domain`. */
export function googleAllowed(email: string): boolean {
  const who = email.trim().toLowerCase()
  return GOOGLE_ALLOWED.some(allowed => allowed.startsWith('@') ? who.endsWith(allowed) && who.length > allowed.length : who === allowed)
}

const GOOGLE_ISSUERS = ['https://accounts.google.com', 'accounts.google.com']
const GOOGLE_KEYS_URL = new URL('https://www.googleapis.com/oauth2/v3/certs')

let googleKeys: JWTVerifyGetKey | undefined

/** Google's signing keys, fetched once and refreshed by `jose` when Google rotates them. */
function keys(): JWTVerifyGetKey {
  googleKeys ??= createRemoteJWKSet(GOOGLE_KEYS_URL)
  return googleKeys
}

/** Puts other keys in Google's place, or Google's back with none; for tests. */
export function useGoogleKeys(replacement?: JWTVerifyGetKey): void {
  googleKeys = replacement
}

/**
 * The verified email a Google ID token was issued for, or undefined when the token is not Google's, is for another
 * client, has expired, or its email is not verified.
 * @param idToken - The token the phone got from Google.
 * @param clientId - The server's web client ID, the token's required audience.
 */
export async function googleEmail(idToken: string, clientId: string): Promise<string | undefined> {
  if (!idToken || !clientId)
    return undefined
  try {
    const { payload } = await jwtVerify(idToken, keys(), { issuer: GOOGLE_ISSUERS, audience: clientId, algorithms: ['RS256'] })
    return payload.email_verified === true && typeof payload.email === 'string' ? payload.email : undefined
  }
  catch {
    return undefined
  }
}
