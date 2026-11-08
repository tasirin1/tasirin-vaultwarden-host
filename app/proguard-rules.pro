

-keepattributes Signature,InnerClasses,*Annotation*

# Audit 1493: tiga -keep class manual (FileShareProvider,
# BootReceiver, AlarmReceiver) dihapus — komponen manifest
# otomatis dijaga aturan keep bawaan AAPT, dan tak ada refleksi
# ke kelas aplikasi sendiri (Class.forName hanya ke kelas
# framework: android.system.Os, java.util.Base64, Process).
