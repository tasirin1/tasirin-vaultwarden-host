# Aplikasi ini Java murni (tanpa serialization/reflection ke kelas sendiri),
# jadi aturan default R8 Android sudah cukup.
#
# Satu-satunya reflection adalah java.lang.Process#pid (API platform) di
# ServerService - R8 tidak menyentuh kelas platform, tidak perlu keep rule.

# Jaga atribut generik/anotasi agar R8 tidak merusak metadata kelas.
-keepattributes Signature,InnerClasses,*Annotation*

# Jangan hapus/rename komponen yang dirujuk AndroidManifest (provider/receiver),
# karena dibuat sistem via reflection berdasarkan nama kelas.
-keep class com.tasirin.vaultwardenhost.FileShareProvider { *; }
-keep class com.tasirin.vaultwardenhost.BootReceiver { *; }
-keep class com.tasirin.vaultwardenhost.AlarmReceiver { *; }
