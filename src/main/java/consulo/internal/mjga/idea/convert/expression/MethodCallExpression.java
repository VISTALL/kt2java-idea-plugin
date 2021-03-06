package consulo.internal.mjga.idea.convert.expression;

import consulo.internal.mjga.idea.convert.GeneratedElement;

import java.util.List;

/**
 * @author VISTALL
 * @since 2020-07-08
 */
public class MethodCallExpression extends CallExpression
{
	public MethodCallExpression(GeneratedElement call, List<GeneratedElement> arguments)
	{
		super(call, arguments);
	}

	@Override
	public String getTemplate()
	{
		return "$L($L)";
	}
}
