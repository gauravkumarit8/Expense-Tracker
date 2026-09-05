# Add project specific ProGuard rules here.
# Keep Room entities/DAOs
-keep class com.autoexpensetracker.data.** { *; }

# sqlcipher-android (migrated 2026-09-04 from android-database-sqlcipher,
# see REQUIREMENTS.md ยง10.6) uses JNI/reflection internally; keep its
# classes intact under R8 minification. This project has never exercised a
# real minified release build before this change (Open Items: "only ever
# built debug so far") — these rules are a proactive addition, not
# something carried over from a previously-working release build, so
# verify the actual release build still opens the encrypted DB correctly.
-keep,includedescriptorclasses class net.zetetic.database.** { *; }
-keep,includedescriptorclasses interface net.zetetic.database.** { *; }
