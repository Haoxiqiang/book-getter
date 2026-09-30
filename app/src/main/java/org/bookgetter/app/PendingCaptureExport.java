package org.bookgetter.app;

import com.google.gson.Gson;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Binds a document picker result to one persisted capture job generation. */
record PendingCaptureExport(String generation, boolean epub) {
    private static final Gson JSON = new Gson();

    static PendingCaptureExport forJob(CaptureJobStore.Job job) {
        if (job.generation() != null && !job.generation().isBlank())
            return new PendingCaptureExport(job.generation(), job.epub());
        // Jobs written before generation tokens were added can still resume after recreation.
        String identity = JSON.toJson(new Object[] {job.sourceId(), job.sourceHash(), job.book(),
                job.catalog(), job.selected(), job.epub()});
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(identity.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte value : digest) hex.append(Character.forDigit((value >>> 4) & 15, 16))
                    .append(Character.forDigit(value & 15, 16));
            return new PendingCaptureExport(hex.toString(), job.epub());
        } catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }

    boolean matches(CaptureJobStore.Job job, boolean resultEpub) {
        return epub == resultEpub && equals(forJob(job));
    }

    String serialize() { return JSON.toJson(this); }

    static PendingCaptureExport restore(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            PendingCaptureExport parsed = JSON.fromJson(json, PendingCaptureExport.class);
            if (parsed.generation() == null || parsed.generation().isBlank()
                    || parsed.generation().length() > 128) return null;
            return parsed;
        } catch (RuntimeException invalid) { return null; }
    }
}
