# StringCast SDK consumer rules (applied automatically to apps that depend on the AAR).

# StringCast.uploadLocalStrings() and the draft-mode missing-key filter enumerate the app's
# R$string / R$plurals / R$array classes by reflection. Both features only run in draft
# (debug) builds, which are normally not minified. If you minify a build that has
# draftMode = true, these rules keep the fields so the enumeration still works.
-keepclassmembers class **.R$string { public static int *; }
-keepclassmembers class **.R$plurals { public static int *; }
-keepclassmembers class **.R$array { public static int *; }

# The SDK resolves the original resource name of every R.string id at runtime via
# Resources.getResourceEntryName(id). R8 resource shrinking must therefore not rename
# resource entries (it doesn't by default; do not enable resource obfuscation).
