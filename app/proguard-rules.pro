# Lyrify release shrinking rules.
#
# Nothing extra is needed today: the activity and the notification listener are
# referenced from the manifest, which AGP keeps automatically, and the app uses no
# reflection. kotlinx.serialization, DataStore, coroutines and Compose ship their own
# consumer rules.
#
# Add -keep rules here if a future change loads classes by name.
