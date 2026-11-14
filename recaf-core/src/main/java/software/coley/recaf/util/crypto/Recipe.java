package software.coley.recaf.util.crypto;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Runs a chain of {@link CryptoStep}s, each consuming the previous step's output.
 */
public final class Recipe {
	private Recipe() {}

	/**
	 * @param steps
	 * 		Steps to run in order.
	 * @param input
	 * 		Input to the first step.
	 *
	 * @return Outcome of each step that ran. Steps after a failing step do not run.
	 */
	@Nonnull
	public static Result run(@Nonnull List<? extends CryptoStep> steps, @Nonnull byte[] input) {
		List<StepOutcome> outcomes = new ArrayList<>(steps.size());
		byte[] current = input;
		for (CryptoStep step : steps) {
			try {
				current = step.apply(current);
				outcomes.add(new StepOutcome(step, current, null));
			} catch (CryptoException ex) {
				outcomes.add(new StepOutcome(step, null, ex.getMessage()));
				return new Result(input, outcomes, null);
			} catch (RuntimeException ex) {
				outcomes.add(new StepOutcome(step, null, "Unexpected error: " + ex));
				return new Result(input, outcomes, null);
			}
		}
		return new Result(input, outcomes, current);
	}

	/**
	 * @param input
	 * 		Recipe input.
	 * @param steps
	 * 		Outcome of each step that ran.
	 * @param output
	 * 		Final output, or {@code null} when a step failed.
	 */
	public record Result(@Nonnull byte[] input, @Nonnull List<StepOutcome> steps, @Nullable byte[] output) {
		/**
		 * @return {@code true} when every step succeeded.
		 */
		public boolean isSuccess() {
			return output != null;
		}

		/**
		 * @return Message of the failing step, or {@code null} when every step succeeded.
		 */
		@Nullable
		public String error() {
			return steps.isEmpty() ? null : steps.getLast().error();
		}
	}

	/**
	 * @param step
	 * 		Step that ran.
	 * @param output
	 * 		Step output, or {@code null} when it failed.
	 * @param error
	 * 		Failure message, or {@code null} when it succeeded.
	 */
	public record StepOutcome(@Nonnull CryptoStep step, @Nullable byte[] output, @Nullable String error) {}
}
