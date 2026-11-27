package com.tasirin.vaultwardenhost;

// Audit 1478: memo kecocokan versi dua tahap — dulu tersalin di
// MainActivity dan SettingsActivity (enam field + dua blok
// if/else per activity). Tahap 1 membandingkan versi binary yang
// berjalan dengan versi tertunda; tahap 2 membandingkan versi
// saat ini (cadangan) dengan versi tertunda. Kunci memo memakai
// bentuk "" untuk null, sama seperti salinan asalnya.
final class MemoVersiCocok {

    private String a1 = "\0";
    private String b1 = "\0";
    private boolean hasil1 = false;
    private String a2 = "\0";
    private String b2 = "\0";
    private boolean hasil2 = false;

    boolean cocokTahap1(String versi, String pend) {
        String a = versi == null ? "" : versi;
        // Audit 2805: pend dinormalkan seperti versi - dulu
        // pend.equals() meledak NPE bila pemanggil mengoper
        // null (pemanggil kini mengawal, tapi laten).
        String b = pend == null ? "" : pend;
        if (a.equals(a1) && b.equals(b1)) {
            return hasil1;
        }
        boolean cocok = Updater.versiCocok(versi, pend);
        a1 = a;
        b1 = b;
        hasil1 = cocok;
        return cocok;
    }

    boolean cocokTahap2(String versi, String pend) {
        String a = versi == null ? "" : versi;
        String b = pend == null ? "" : pend;
        if (a.equals(a2) && b.equals(b2)) {
            return hasil2;
        }
        boolean cocok = Updater.versiCocok(versi, pend);
        a2 = a;
        b2 = b;
        hasil2 = cocok;
        return cocok;
    }
}
