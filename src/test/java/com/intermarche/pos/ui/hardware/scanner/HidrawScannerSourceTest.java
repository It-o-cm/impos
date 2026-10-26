package com.intermarche.pos.ui.hardware.scanner;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

/** LC-11-01: unit checks of raw scanner file lifecycle using disposable files, without a hardware device. */
class HidrawScannerSourceTest {
    /** LC-11-01: opening an existing ordinary file returns a usable descriptor that is always closed. */
    @Test
    void existingFileCanBeOpenedAndClosed(@TempDir Path directory) throws Exception {
        Path node = Files.write(directory.resolve("scanner"), new byte[0]);
        var source = new HidrawScannerSource(node.toString(), code -> {});
        int descriptor = source.open();
        assertTrue(descriptor >= 0);
        source.close(descriptor);
    }
    /** LC-11-01: an absent node returns the failure descriptor without opening any real device. */
    @Test
    void missingFileCannotBeOpened(@TempDir Path directory) {
        var source = new HidrawScannerSource(directory.resolve("missing").toString(), code -> {});
        assertTrue(source.open() < 0);
    }
}
