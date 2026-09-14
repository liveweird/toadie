import { afterEach, describe, expect, test, vi } from "vitest";
import { useState } from "react";
import { Transition } from "@mantine/core";
import { act, renderWithProviders, screen } from "./render";

/**
 * Pins the test-environment rule every page test relies on: a Mantine Transition completes
 * SYNCHRONOUSLY, so no 150 ms timer can outlive RTL's cleanup and fire "window is not defined"
 * after happy-dom is torn down (the CI race PRs #26/#40/#38/#63 hit). `Transition` calls
 * `useTransition` unconditionally BEFORE its `env="test"` render short-circuit, so the check has
 * to watch the hook's timers, not the DOM — under fake timers a non-reduced transition leaves
 * exactly one pending timeout after the rAF→rAF chain.
 */
function Host() {
  const [mounted, setMounted] = useState(true);
  return (
    <>
      <button type="button" onClick={() => setMounted(false)}>
        hide
      </button>
      <Transition mounted={mounted} transition="fade" duration={150}>
        {(styles) => <div style={styles}>child</div>}
      </Transition>
    </>
  );
}

describe("reduced motion under test", () => {
  afterEach(() => {
    vi.useRealTimers();
  });

  test("the reduced-motion media query answers true, other queries still delegate", () => {
    expect(window.matchMedia("(prefers-reduced-motion: reduce)").matches).toBe(true);
    expect(window.matchMedia("(prefers-color-scheme: dark)").matches).toBe(false);
  });

  test("a Transition flip leaves no pending timer under the shared wrapper", () => {
    vi.useFakeTimers({
      toFake: ["setTimeout", "clearTimeout", "requestAnimationFrame", "cancelAnimationFrame"],
    });
    renderWithProviders(<Host />);
    // Let `useMediaQuery`'s effect read matchMedia and commit `reduceMotion = true`.
    act(() => {
      vi.advanceTimersByTime(50);
    });
    act(() => screen.getByText("hide").click());
    // Run the rAF→rAF chain (fake rAF fires every 16 ms) but stop short of the 150 ms timeout.
    act(() => {
      vi.advanceTimersByTime(60);
    });
    expect(vi.getTimerCount()).toBe(0);
    expect(screen.queryByText("child")).toBeNull();
  });
});
