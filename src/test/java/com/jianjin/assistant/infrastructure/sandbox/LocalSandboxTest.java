package com.jianjin.assistant.infrastructure.sandbox;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LocalSandboxTest {

    @Test
    void parsesArgumentsWithoutInvokingAShell() {
        assertEquals(List.of("echo", "hello world"), LocalSandbox.parseArgv("echo \"hello world\""));
    }

    @Test
    void rejectsShellOperatorsExpansionsAndEscapes() {
        assertThrows(IllegalArgumentException.class, () -> LocalSandbox.parseArgv("echo ok; rm file"));
        assertThrows(IllegalArgumentException.class, () -> LocalSandbox.parseArgv("echo $HOME"));
        assertThrows(IllegalArgumentException.class, () -> LocalSandbox.parseArgv("r\\m file"));
    }
}
