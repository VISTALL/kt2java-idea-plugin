package consulo.internal.mjga.idea.convert.expression;

import com.squareup.javapoet.CodeBlock;
import consulo.internal.mjga.idea.convert.MemberConverter;

/**
 * @author VISTALL
 * @since 2020-07-08
 */
public class ReferenceExpression extends Expression
{
	private final String myReferenceName;

	public ReferenceExpression(String referenceName)
	{
		myReferenceName = referenceName;
	}

	@Override
	public CodeBlock generate(boolean needNewLine)
	{
		return CodeBlock.of(wrap("$L", needNewLine), MemberConverter.safeName(myReferenceName));
	}
}
