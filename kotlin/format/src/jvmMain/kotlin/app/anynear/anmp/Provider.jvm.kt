package app.anynear.anmp

import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.providers.jdk.JDK
import org.bouncycastle.jce.provider.BouncyCastleProvider

// BouncyCastle: the plain JDK cannot get the Ed25519 public key from a private key.
internal actual val anmpProvider: CryptographyProvider = CryptographyProvider.JDK(BouncyCastleProvider())
