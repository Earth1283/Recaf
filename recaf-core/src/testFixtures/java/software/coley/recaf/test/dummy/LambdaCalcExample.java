package software.coley.recaf.test.dummy;

public class LambdaCalcExample {
	private static void printExample() {
		Runnable example = () -> System.out.println("earlier lambda");
		example.run();
	}

	public static void main(String[] args) {
		MathOperation maximum = LambdaCalcExample::maximum;
		MathOperation addition = (a, b) -> a + b;
		MathOperation multiplication = (a, b) -> a * b;
		maximum.operate(5, 3);
		addition.operate(5, 3);
		multiplication.operate(5, 3);
	}

	private static int maximum(int a, int b) {
		return Math.max(a, b);
	}

	interface MathOperation {
		int operate(int a, int b);
	}
}
