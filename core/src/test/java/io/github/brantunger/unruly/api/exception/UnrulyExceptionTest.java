package io.github.brantunger.unruly.api.exception;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class UnrulyExceptionTest {

    @Test
    public void testUnrulyException() {
        UnrulyException ex1 = new UnrulyException("msg1");
        assertEquals("msg1", ex1.getMessage());

        Throwable cause = new RuntimeException();
        UnrulyException ex2 = new UnrulyException("msg2", cause);
        assertEquals("msg2", ex2.getMessage());
        assertEquals(cause, ex2.getCause());
    }

    @Test
    public void testRuleCompilationException() {
        RuleCompilationException ex1 = new RuleCompilationException("msg1");
        assertEquals("msg1", ex1.getMessage());

        Throwable cause = new RuntimeException();
        RuleCompilationException ex2 = new RuleCompilationException("msg2", cause);
        assertEquals("msg2", ex2.getMessage());
        assertEquals(cause, ex2.getCause());
    }

    @Test
    public void testRuleExecutionException() {
        RuleExecutionException ex1 = new RuleExecutionException("msg1");
        assertEquals("msg1", ex1.getMessage());

        Throwable cause = new RuntimeException();
        RuleExecutionException ex2 = new RuleExecutionException("msg2", cause);
        assertEquals("msg2", ex2.getMessage());
        assertEquals(cause, ex2.getCause());
    }
}
