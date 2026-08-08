# Aplikasi ini Java murni (tanpa serialization/reflection ke kelas sendiri),
# jadi aturan default R8 Android sudah cukup.
#
# Satu-satunya reflection adalah java.lang.Process#pid (API platform) di
# ServerService - R8 tidak menyentuh kelas platform, tidak perlu keep rule.
