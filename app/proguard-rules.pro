# Add project specific ProGuard rules here.
# Keep Room entities/DAOs
-keep class com.autoexpensetracker.data.** { *; }

# WorkManager's default WorkerFactory instantiates Worker subclasses via
# reflection (Class.forName on the fully-qualified class name stored at
# enqueue time), completely separate from the AndroidManifest-declared
# component keep rules R8 applies automatically. Without this, a minified
# release build would silently fail to run ParseAndStoreWorker (the actual
# notification-capture -> parse -> DB-write pipeline this whole app exists
# to do) and ReminderCheckWorker — no crash, no visible error, background
# work just quietly stops happening. Found while auditing for the
# never-yet-tested minified release build (see the SQLCipher comment
# below) — this one specifically would NOT have surfaced during casual
# testing unless someone waited to see if captured transactions ever
# actually appeared.
-keep class com.autoexpensetracker.worker.** { *; }

# sqlcipher-android (migrated 2026-09-04 from android-database-sqlcipher,
# see REQUIREMENTS.md ยง10.6) uses JNI/reflection internally; keep its
# classes intact under R8 minification. This project has never exercised a
# real minified release build before this change (Open Items: "only ever
# built debug so far") — these rules are a proactive addition, not
# something carried over from a previously-working release build, so
# verify the actual release build still opens the encrypted DB correctly.
-keep,includedescriptorclasses class net.zetetic.database.** { *; }
-keep,includedescriptorclasses interface net.zetetic.database.** { *; }
