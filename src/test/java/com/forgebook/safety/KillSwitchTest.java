package com.forgebook.safety;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class KillSwitchTest {

    @AfterEach
    void resetState() {
        KillSwitch.setDisabled(false);
    }

    @Test
    void defaultStateIsEnabled() {
        assertFalse(KillSwitch.isDisabled());
    }

    @Test
    void setDisabledTrue_flipsFlag() {
        KillSwitch.setDisabled(true);
        assertTrue(KillSwitch.isDisabled());
    }

    @Test
    void setDisabledFalse_restoresFlag() {
        KillSwitch.setDisabled(true);
        KillSwitch.setDisabled(false);
        assertFalse(KillSwitch.isDisabled());
    }

    @Test
    void concurrentReadSeesLatestWrite() throws InterruptedException {
        KillSwitch.setDisabled(false);
        Thread writer = new Thread(() -> KillSwitch.setDisabled(true));
        writer.start();
        writer.join();
        assertTrue(KillSwitch.isDisabled());
    }
}
