package app.anynear.anmp

import dev.whyoleg.cryptography.CryptographyProvider

// WebCrypto (browser and Node.js). Only asynchronous operations.
internal actual val anmpProvider: CryptographyProvider = CryptographyProvider.Default
