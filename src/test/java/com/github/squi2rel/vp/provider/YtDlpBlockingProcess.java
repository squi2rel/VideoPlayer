package com.github.squi2rel.vp.provider;

public final class YtDlpBlockingProcess {
    private YtDlpBlockingProcess() {
    }

    public static void main(String[] args) throws InterruptedException {
        while (true) {
            Thread.sleep(30_000L);
        }
    }
}
