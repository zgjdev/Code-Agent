package com.codeagent.tool;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CommandShellTest {

    @Test
    void selectsPowerShellOnWindows() {
        assertEquals(
                List.of("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", "Get-Location"),
                CommandShell.forOsName("Windows 11").command("Get-Location"));
    }

    @Test
    void selectsBashOnUnixLikeSystems() {
        assertEquals(
                List.of("bash", "-c", "pwd"),
                CommandShell.forOsName("Linux").command("pwd"));
    }
}
