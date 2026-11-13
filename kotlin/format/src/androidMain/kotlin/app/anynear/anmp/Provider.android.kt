package app.anynear.anmp

import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.providers.jdk.JDK
import org.bouncycastle.jce.provider.BouncyCastleProvider

// Own BouncyCastle instance instead of the stripped system "BC" (as in the app).
internal actual val anmpProvider: CryptographyProvider = CryptographyProvider.JDK(BouncyCastleProvider())
