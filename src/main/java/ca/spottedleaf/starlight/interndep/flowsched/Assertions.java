/*
 * Vendored from FlowSched (https://github.com/RelativityMC/FlowSched), commit a60f952, package
 * com.ishland.flowsched.util, relocated for Solix. Copyright (c) 2023 ishland. MIT License, see LICENSE-FlowSched.txt.
 */
package ca.spottedleaf.starlight.interndep.flowsched;

public class Assertions {

    public static void assertTrue(boolean value, String message) {
        if (!value) {
            final AssertionError error = new AssertionError(message);
            error.printStackTrace();
            throw error;
        }
    }

    public static void assertTrue(boolean state, String format, Object... args) {
        if (!state) {
            final AssertionError error = new AssertionError(String.format(format, args));
            error.printStackTrace();
            throw error;
        }
    }

    public static void assertTrue(boolean value) {
        if (!value) {
            final AssertionError error = new AssertionError();
            error.printStackTrace();
            throw error;
        }
    }

}
