package anmp

// Spaces of the radio way run by anynear (ADR 0051, 0054 §5a, ADR 0061 decision 12), as in kotlin/core Config.kt.
// Public, not secrets. The grants for anyone that let clients write there are in kotlin/core Config.kt; this module
// only needs the spaces.
const (
	// RadioInboxSpace holds every heard radio packet in the public form, one entry each.
	RadioInboxSpace = "Pg1iLVMX9oL8p-aPTdbYLVlpwOqoaJ18W8ZophDaiK8"
	// RadioHeardSpace holds the gateways that heard an ask.
	RadioHeardSpace = "_VjIfS9sTwox4K8mt5Rl0Tfqq4qUbbPjPyPjjWf4Ovs"
	// RadioOutboxSpace holds packets a patron sends through one gateway.
	RadioOutboxSpace = "-0l_j3DMEJp7BqdVBqWOwW-gYtqOfG3ziieoSWprEEY"
)

// RadioInboxLifetimeSeconds is the life of a radio inbox entry: until = ts + 30 days (ANDP max_until).
const RadioInboxLifetimeSeconds = 30 * 24 * 3600
