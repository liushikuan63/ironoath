/** Attach rejection handling before an action can fail and close the browser.
 * Keep the original promise so callers awaiting the response still receive its original error.
 */
export function retainResponseWait(pending) {
  void pending.catch(() => {});
  return pending;
}
