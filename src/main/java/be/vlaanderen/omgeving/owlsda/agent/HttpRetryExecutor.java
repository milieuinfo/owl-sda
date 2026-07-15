package be.vlaanderen.omgeving.owlsda.agent;

import java.util.function.Predicate;

/**
 * Retries a call with exponential backoff, letting the caller decide which exceptions are
 * retryable. An exception the {@code isRetryable} predicate rejects propagates immediately without
 * retrying.
 */
public final class HttpRetryExecutor {

  private HttpRetryExecutor() {}

  @FunctionalInterface
  public interface RetryableCall<T> {
    T call() throws Exception;
  }

  @FunctionalInterface
  public interface RetryListener {
    void onRetry(int attempt, int maxRetries, Exception cause);
  }

  /**
   * Invokes {@code call} up to {@code maxRetries + 1} times total. If it throws an exception
   * matching {@code isRetryable}, waits {@code baseDelayMs * 2^attempt} and retries, unless this
   * was the final attempt, in which case the exception is rethrown.
   */
  public static <T> T retry(
      int maxRetries,
      long baseDelayMs,
      Predicate<Exception> isRetryable,
      RetryListener onRetry,
      RetryableCall<T> call)
      throws Exception {
    Exception lastError = null;
    for (int attempt = 0; attempt <= maxRetries; attempt++) {
      try {
        return call.call();
      } catch (Exception e) {
        if (!isRetryable.test(e)) {
          throw e;
        }
        lastError = e;
        if (attempt < maxRetries) {
          if (onRetry != null) {
            onRetry.onRetry(attempt, maxRetries, e);
          }
          try {
            Thread.sleep(baseDelayMs * (1L << attempt));
          } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw interrupted;
          }
        }
      }
    }
    throw lastError;
  }
}
