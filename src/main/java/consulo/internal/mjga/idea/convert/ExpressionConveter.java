package consulo.internal.mjga.idea.convert;

import com.intellij.openapi.util.Couple;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.tree.IElementType;
import com.intellij.util.containers.ContainerUtil;
import com.squareup.javapoet.ArrayTypeName;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.TypeName;
import consulo.internal.mjga.idea.convert.expression.*;
import consulo.internal.mjga.idea.convert.generate.KtToJavaClassBinder;
import consulo.internal.mjga.idea.convert.library.FunctionRemapper;
import consulo.internal.mjga.idea.convert.statement.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.kotlin.descriptors.*;
import org.jetbrains.kotlin.descriptors.impl.LocalVariableDescriptor;
import org.jetbrains.kotlin.descriptors.impl.SyntheticFieldDescriptor;
import org.jetbrains.kotlin.descriptors.impl.TypeAliasConstructorDescriptor;
import org.jetbrains.kotlin.idea.caches.resolve.ResolutionUtils;
import org.jetbrains.kotlin.lexer.KtTokens;
import org.jetbrains.kotlin.load.java.sam.SamConstructorDescriptor;
import org.jetbrains.kotlin.name.FqName;
import org.jetbrains.kotlin.name.Name;
import org.jetbrains.kotlin.psi.*;
import org.jetbrains.kotlin.resolve.BindingContext;
import org.jetbrains.kotlin.resolve.calls.model.ResolvedCall;
import org.jetbrains.kotlin.resolve.calls.model.ResolvedValueArgument;
import org.jetbrains.kotlin.resolve.lazy.BodyResolveMode;
import org.jetbrains.kotlin.resolve.lazy.descriptors.LazyClassDescriptor;
import org.jetbrains.kotlin.resolve.source.KotlinSourceElement;
import org.jetbrains.kotlin.resolve.source.PsiSourceFile;
import org.jetbrains.kotlin.types.KotlinType;

import java.util.*;

/**
 * @author VISTALL
 * @since 2020-06-24
 */
public class ExpressionConveter extends KtVisitorVoid
{
	private final ConvertContext myContext;

	@NotNull
	public static GeneratedElement convertNonnull(@Nullable PsiElement element, @NotNull ConvertContext context)
	{
		if(element == null)
		{
			return new ConstantExpression("\"unsupported\"");
		}
		ExpressionConveter conveter = new ExpressionConveter(context);
		element.accept(conveter);
		GeneratedElement generatedElement = conveter.myGeneratedElement;
		if(generatedElement == null)
		{
			generatedElement = new ConstantExpression("\"unsupported '" + element.getText() + "' expression\"");
		}
		return generatedElement;
	}

	private GeneratedElement myGeneratedElement;

	public ExpressionConveter(ConvertContext context)
	{
		myContext = context;
	}

	@NotNull
	private GeneratedElement convertNonnull(@Nullable PsiElement element)
	{
		return convertNonnull(element, myContext);
	}

	@Override
	public void visitElement(@NotNull PsiElement element)
	{
		super.visitElement(element);
	}

	@Override
	public void visitSimpleNameExpression(KtSimpleNameExpression expression)
	{
		if(expression instanceof KtNameReferenceExpression)
		{
			String referencedName = expression.getReferencedName();

			myGeneratedElement = new ReferenceExpression(referencedName);

			BindingContext context = ResolutionUtils.analyze(expression);

			DeclarationDescriptor receiverResult = context.get(BindingContext.REFERENCE_TARGET, expression);
			if(receiverResult == null)
			{
				return;
			}

			@Nullable TypeName typeName = TypeConverter.convertKotlinDescriptor(receiverResult, false);
			if(typeName != null)
			{
				myGeneratedElement = new TypeReferenceExpression(typeName);
			}

			if(receiverResult instanceof SyntheticFieldDescriptor)
			{
				PropertyDescriptor propertyDescriptor = ((SyntheticFieldDescriptor) receiverResult).getPropertyDescriptor();

				myGeneratedElement = new ReferenceExpression("__" + propertyDescriptor.getName());
			}
			else if(receiverResult instanceof PropertyDescriptor)
			{
				if(isClassMember(receiverResult, "kotlin.Array", "size") ||
						isClassMember(receiverResult, "kotlin.ByteArray", "size") ||
						isClassMember(receiverResult, "kotlin.ShortArray", "size") ||
						isClassMember(receiverResult, "kotlin.LongArray", "size") ||
						isClassMember(receiverResult, "kotlin.DoubleArray", "size") ||
						isClassMember(receiverResult, "kotlin.FloatArray", "size") ||
						isClassMember(receiverResult, "kotlin.IntArray", "size"))
				{
					myGeneratedElement = new ReferenceExpression("length");
				}
				else
				{
					PropertyGetterDescriptor getter = ((PropertyDescriptor) receiverResult).getGetter();
					if(getter != null && ((PropertyDescriptor) receiverResult).getVisibility() != Visibilities.PRIVATE && !((PropertyDescriptor) receiverResult).isConst())
					{
						String methodName = "get" + StringUtil.capitalize(receiverResult.getName().asString());
						myGeneratedElement = new MethodCallExpression(new ReferenceExpression(methodName), Collections.emptyList());
					}

					myGeneratedElement = modifyCallIfPackageOwner((PropertyDescriptor) receiverResult, myGeneratedElement);
				}
			}
		}
	}

	private boolean isClassMember(DeclarationDescriptor descriptor, String fqName, String name)
	{
		CallableMemberDescriptor callableMemberDescriptor = (CallableMemberDescriptor) descriptor;

		if(name.equals(callableMemberDescriptor.getName().asString()))
		{
			DeclarationDescriptor containingDeclaration = callableMemberDescriptor.getContainingDeclaration();

			if(containingDeclaration instanceof ClassDescriptor)
			{
				FqName requireFqName = FqName.fromSegments(StringUtil.split(fqName, "."));

				return buildFqName(containingDeclaration).equals(requireFqName);
			}
		}
		return false;
	}

	private FqName buildFqName(DeclarationDescriptor descriptor)
	{
		if(descriptor instanceof ClassDescriptor)
		{
			DeclarationDescriptor containingDeclaration = descriptor.getContainingDeclaration();

			return buildFqName(containingDeclaration).child(descriptor.getName());
		}
		else if(descriptor instanceof PackageFragmentDescriptor)
		{
			return ((PackageFragmentDescriptor) descriptor).getFqName();
		}

		return FqName.topLevel(Name.identifier("error"));
	}

	private GeneratedElement modifyCallIfPackageOwner(CallableDescriptor receiverResult, GeneratedElement element)
	{
		if(receiverResult.getContainingDeclaration() instanceof PackageFragmentDescriptor)
		{
			SourceFile containingFile = receiverResult.getSource().getContainingFile();

			PsiFile file = containingFile instanceof PsiSourceFile ? ((PsiSourceFile) containingFile).getPsiFile() : null;

			if(file instanceof KtFile)
			{
				@NotNull KtToJavaClassBinder classBinder = myContext.bind((KtFile) file);

				ClassName name = ClassName.get(classBinder.getPackageName(), classBinder.getClassName());

				return new StaticTypeQualifiedExpression(name, element);
			}
		}

		return element;
	}

	@Override
	public void visitPrefixExpression(KtPrefixExpression expression)
	{
		KtExpression baseExpression = expression.getBaseExpression();

		@NotNull GeneratedElement generatedElement = convertNonnull(baseExpression);

		IElementType operationToken = expression.getOperationToken();

		// !test
		if(operationToken == KtTokens.EXCL)
		{
			myGeneratedElement = new PrefixExpression("!", generatedElement);
		}
	}

	@Override
	public void visitIsExpression(KtIsExpression expression)
	{
		boolean isNot = expression.getOperationReference().getReferencedNameElementType() == KtTokens.NOT_IS;

		GeneratedElement generatedElement = convertNonnull(expression.getLeftHandSide());

		BindingContext context = ResolutionUtils.analyze(expression.getTypeReference());

		KotlinType type = context.get(BindingContext.TYPE, expression.getTypeReference());

		myGeneratedElement = new InstanceOfExpression(generatedElement, TypeConverter.convertKotlinType(type));

		if(isNot)
		{
			myGeneratedElement = new PrefixExpression("!", new ParExpression(myGeneratedElement));
		}
	}

	@Override
	public void visitPostfixExpression(KtPostfixExpression expression)
	{
		KtExpression baseExpression = expression.getBaseExpression();

		@NotNull GeneratedElement generatedElement = convertNonnull(baseExpression);

		IElementType operationToken = expression.getOperationToken();

		// assertion - just ignore
		if(operationToken == KtTokens.EXCLEXCL)
		{
			myGeneratedElement = generatedElement;
		}
	}

	// TODO better handle safe access
	@Override
	public void visitSafeQualifiedExpression(KtSafeQualifiedExpression expression)
	{
		@NotNull GeneratedElement left = convertNonnull(expression.getReceiverExpression());
		@NotNull GeneratedElement right = convertNonnull(expression.getSelectorExpression());

		myGeneratedElement = new QualifiedExpression(left, right);
	}

	@Override
	public void visitDotQualifiedExpression(KtDotQualifiedExpression expression)
	{
		BindingContext context = ResolutionUtils.analyze(expression);

		KtExpression receiver = expression.getReceiverExpression();

		GeneratedElement receiverGenerate = convertNonnull(receiver);

		GeneratedElement selectorGenerate = convertNonnull(expression.getSelectorExpression());

		if(receiver instanceof KtNameReferenceExpression)
		{
			DeclarationDescriptor receiverResult = context.get(BindingContext.REFERENCE_TARGET, (KtNameReferenceExpression) receiver);

			if(receiverResult instanceof LazyClassDescriptor)
			{
				SourceElement source = ((LazyClassDescriptor) receiverResult).getSource();

				if(source instanceof KotlinSourceElement)
				{
					KtElement psi = ((KotlinSourceElement) source).getPsi();

					if(psi instanceof KtObjectDeclaration)
					{
						receiverGenerate = new QualifiedExpression(receiverGenerate, new ReferenceExpression("INSTANCE"));
					}
				}
			}
		}

		ResolvedCall<? extends CallableDescriptor> call = ResolutionUtils.resolveToCall(expression.getSelectorExpression(), BodyResolveMode.FULL);

		GeneratedElement result = new QualifiedExpression(receiverGenerate, selectorGenerate);

		// extension call
		if(call != null && call.getExtensionReceiver() != null)
		{
			GeneratedElement targetSelector = selectorGenerate;
			TypeName qualifiedType = null;

			if(targetSelector instanceof StaticTypeQualifiedExpression)
			{
				qualifiedType = ((StaticTypeQualifiedExpression) targetSelector).getTypeName();
				targetSelector = ((StaticTypeQualifiedExpression) targetSelector).getSelector();
			}

			if(targetSelector instanceof MethodCallExpression)
			{
				GeneratedElement oldCall = ((MethodCallExpression) targetSelector).getCall();
				List<GeneratedElement> oldArguments = ((MethodCallExpression) targetSelector).getArguments();

				ArrayList<GeneratedElement> newArgs = new ArrayList<>(oldArguments);
				newArgs.add(0, receiverGenerate);

				MethodCallExpression newCall = new MethodCallExpression(oldCall, newArgs);
				result = qualifiedType == null ? newCall : new StaticTypeQualifiedExpression(qualifiedType, newCall);
			}
		}

		myGeneratedElement = result;
	}

	@Override
	public void visitProperty(KtProperty property)
	{
		KtExpression initializer = property.getInitializer();

		BindingContext context = ResolutionUtils.analyze(property);

		DeclarationDescriptor declarationDescriptor = context.get(BindingContext.DECLARATION_TO_DESCRIPTOR, property);

		if(declarationDescriptor == null)
		{
			return;
		}

		GeneratedElement init = initializer == null ? null : convertNonnull(initializer);

		if(declarationDescriptor instanceof LocalVariableDescriptor)
		{
			KotlinType type = ((LocalVariableDescriptor) declarationDescriptor).getType();

			TypeName typeName = TypeConverter.convertKotlinType(type);

			myGeneratedElement = new LocalVariableStatement(typeName, property.getName(), init);
		}
	}

	@Override
	public void visitStringTemplateExpression(KtStringTemplateExpression expression)
	{
		List<GeneratedElement> expressions = new ArrayList<>();

		for(KtStringTemplateEntry entry : expression.getEntries())
		{
			if(entry instanceof KtStringTemplateEntryWithExpression)
			{
				expressions.add(convertNonnull(entry.getExpression()));
			}
			else
			{
				expressions.add(new ConstantExpression("\"" + entry.getText() + "\""));
			}
		}

		myGeneratedElement = new StringBuilderExpression(expressions);
	}

	@Override
	public void visitTryExpression(KtTryExpression expression)
	{
		KtBlockExpression tryBlock = expression.getTryBlock();

		@NotNull GeneratedElement tryBlockGen = convertNonnull(tryBlock);

		List<KtCatchClause> catchClauses = expression.getCatchClauses();

		List<TryCatchStatement.Catch> catches = new ArrayList<>();
		for(KtCatchClause clause : catchClauses)
		{
			KtParameter catchParameter = clause.getCatchParameter();

			BindingContext context = ResolutionUtils.analyze(catchParameter);

			ValueDescriptor d = (ValueDescriptor) context.get(BindingContext.DECLARATION_TO_DESCRIPTOR, catchParameter);

			@NotNull GeneratedElement body = convertNonnull(clause.getCatchBody());

			catches.add(new TryCatchStatement.Catch(MemberConverter.safeName(catchParameter.getName()), TypeConverter.convertKotlinType(d.getType()), body));
		}

		myGeneratedElement = new TryCatchStatement(tryBlockGen, catches);
	}

	@Override
	public void visitThisExpression(KtThisExpression expression)
	{
		myGeneratedElement = new ThisExpression();
	}

	@Override
	public void visitSuperExpression(KtSuperExpression expression)
	{
		myGeneratedElement = new SuperExpression();
	}

	@Override
	public void visitCallExpression(KtCallExpression expression)
	{
		GeneratedElement genCall = convertNonnull(expression.getCalleeExpression());

		ResolvedCall<? extends CallableDescriptor> call = ResolutionUtils.resolveToCall(expression, BodyResolveMode.FULL);

		if(call == null)
		{
			return;
		}

		CallableDescriptor resultingDescriptor = call.getResultingDescriptor();
		if(resultingDescriptor instanceof SamConstructorDescriptor)
		{
			List<? extends LambdaArgument> functionLiteralArguments = call.getCall().getFunctionLiteralArguments();

			if(functionLiteralArguments.isEmpty())
			{
				return;
			}

			LambdaArgument lambdaArgument = functionLiteralArguments.get(0);

			KtLambdaExpression lambdaExpression = lambdaArgument.getLambdaExpression();

			KtBlockExpression bodyExpression = lambdaExpression.getBodyExpression();

			myGeneratedElement = new LambdaExpression(Collections.emptyList(), convertNonnull(bodyExpression));
		}
		else if(resultingDescriptor instanceof ClassConstructorDescriptor || resultingDescriptor instanceof TypeAliasConstructorDescriptor)
		{
			List<GeneratedElement> args = new ArrayList<>();

			List<ResolvedValueArgument> valueArgumentsByIndex = call.getValueArgumentsByIndex();

			for(ResolvedValueArgument valueArgument : valueArgumentsByIndex)
			{
				for(ValueArgument argument : valueArgument.getArguments())
				{
					args.add(convertNonnull(argument.getArgumentExpression()));
				}
			}

			KotlinType returnType = resultingDescriptor.getReturnType();

			TypeName typeName = TypeConverter.convertKotlinType(returnType);

			myGeneratedElement = new NewExpression(typeName, args);
		}
		else
		{
			List<GeneratedElement> args = new ArrayList<>();

			List<ResolvedValueArgument> valueArgumentsByIndex = call.getValueArgumentsByIndex();

			for(ResolvedValueArgument valueArgument : valueArgumentsByIndex)
			{
				for(ValueArgument argument : valueArgument.getArguments())
				{
					args.add(convertNonnull(argument.getArgumentExpression()));
				}
			}

			if(isFunctionFromPackage(resultingDescriptor, "kotlin", "arrayOf"))
			{
				Map<TypeParameterDescriptor, KotlinType> typeArguments = call.getTypeArguments();

				KotlinType first = ContainerUtil.getFirstItem(typeArguments.values());

				ArrayTypeName arrayType = ArrayTypeName.of(TypeConverter.convertKotlinType(first));

				myGeneratedElement = new NewArrayExpression(arrayType, args);
			}
			else
			{
				genCall = FunctionRemapper.remap(call, genCall);

				myGeneratedElement = new MethodCallExpression(genCall, args);

				myGeneratedElement = modifyCallIfPackageOwner(resultingDescriptor, myGeneratedElement);
			}
		}
	}

	private static boolean isFunctionFromPackage(CallableDescriptor callableDescriptor, String packageName, @NotNull String name)
	{
		if(callableDescriptor instanceof FunctionDescriptor && name.equals(callableDescriptor.getName().asString()))
		{
			DeclarationDescriptor containingDeclaration = callableDescriptor.getContainingDeclaration();

			if(containingDeclaration instanceof PackageFragmentDescriptor)
			{
				return packageName.equals(((PackageFragmentDescriptor) containingDeclaration).getFqName().asString());
			}
		}

		return false;
	}

	@Override
	public void visitBinaryExpression(KtBinaryExpression expression)
	{
		KtExpression leftExpr = expression.getLeft();

		GeneratedElement leftGen = convertNonnull(leftExpr);
		GeneratedElement rightGen = convertNonnull(expression.getRight());

		IElementType operationToken = expression.getOperationToken();
		if(operationToken == KtTokens.EQEQ)
		{
			myGeneratedElement = new MethodCallExpression(new StaticTypeQualifiedExpression(TypeName.get(Objects.class), "equals"), Arrays.asList(leftGen, rightGen));
			return;
		}

		if(operationToken == KtTokens.EXCLEQ)
		{
			myGeneratedElement = new PrefixExpression("!", new MethodCallExpression(new StaticTypeQualifiedExpression(TypeName.get(Objects.class), "equals"), Arrays.asList(leftGen, rightGen)));
			return;
		}

		if(operationToken == KtTokens.EQEQEQ)
		{
			myGeneratedElement = new BinaryExpression(leftGen, rightGen, "==");
			return;
		}

		if(operationToken == KtTokens.EXCLEQEQEQ)
		{
			myGeneratedElement = new BinaryExpression(leftGen, rightGen, "!=");
			return;
		}

		if(operationToken == KtTokens.ELVIS)
		{
			BinaryExpression condition = new BinaryExpression(leftGen, new ConstantExpression("null"), "==");
			myGeneratedElement = new TernaryExpression(condition, rightGen, leftGen);
			return;
		}

		ResolvedCall<? extends CallableDescriptor> call = ResolutionUtils.resolveToCall(expression, BodyResolveMode.FULL);

		if(call == null)
		{
			if(leftExpr instanceof KtReferenceExpression)
			{
				BindingContext context = ResolutionUtils.analyze(leftExpr);

				DeclarationDescriptor leftResult = context.get(BindingContext.REFERENCE_TARGET, (KtReferenceExpression) leftExpr);

				if(leftResult instanceof PropertyDescriptor)
				{
					PropertySetterDescriptor setter = ((PropertyDescriptor) leftResult).getSetter();

					if(setter == null || ((PropertyDescriptor) leftResult).getVisibility() == Visibilities.PRIVATE && !((PropertyDescriptor) leftResult).isConst())
					{
						myGeneratedElement = new AssignExpression(convertNonnull(leftExpr), rightGen);
					}
					else
					{
						String methodName = "set" + StringUtil.capitalize(leftResult.getName().asString());
						myGeneratedElement = new MethodCallExpression(new ReferenceExpression(methodName), Arrays.asList(rightGen));
					}
				}
			}
		}

		if(myGeneratedElement != null)
		{
			return;
		}

		if(operationToken == KtTokens.EQ)
		{
			myGeneratedElement = new AssignExpression(leftGen, rightGen);
			return;
		}

		String text = expression.getOperationReference().getText();

		myGeneratedElement = new BinaryExpression(leftGen, rightGen, text);
	}

	@Override
	public void visitIfExpression(KtIfExpression expression)
	{
		GeneratedElement condition = convertNonnull(expression.getCondition());

		GeneratedElement trueBlock = convertNonnull(expression.getThen());

		KtExpression anElse = expression.getElse();

		GeneratedElement falseBlock = anElse == null ? null : convertNonnull(anElse);

		boolean canByTernary = false;

		PsiElement parent = expression.getParent();
		if(parent instanceof KtProperty || parent instanceof KtBinaryExpression || parent instanceof KtValueArgument || parent instanceof KtReturnExpression)
		{
			canByTernary = true;
		}

		if(canByTernary)
		{
			myGeneratedElement = new TernaryExpression(condition, trueBlock, falseBlock);
		}
		else
		{
			myGeneratedElement = new IfStatement(condition, trueBlock, falseBlock);
		}
	}

	@Override
	public void visitConstantExpression(@NotNull KtConstantExpression expression)
	{
		myGeneratedElement = new ConstantExpression(expression.getText());
	}

	@Override
	public void visitReturnExpression(KtReturnExpression expression)
	{
		KtExpression returnedExpression = expression.getReturnedExpression();
		myGeneratedElement = returnedExpression == null ? new ReturnStatement(null) : new ReturnStatement(convertNonnull(returnedExpression));
	}

	@Override
	public void visitThrowExpression(KtThrowExpression expression)
	{
		KtExpression thrownExpression = expression.getThrownExpression();
		myGeneratedElement = new ThrowStatement(convertNonnull(thrownExpression));
	}

	@Override
	public void visitWhenExpression(KtWhenExpression expression)
	{
		GeneratedElement subjectExpression = convertNonnull(expression.getSubjectExpression());

		List<KtWhenEntry> entries = expression.getEntries();

		List<Couple<GeneratedElement>> ifParts = new ArrayList<>();

		GeneratedElement elseItem = null;

		for(KtWhenEntry entry : entries)
		{
			GeneratedElement whenExpr = convertNonnull(entry.getExpression());

			PsiElement elseKeyword = entry.getElseKeyword();

			for(KtWhenCondition ktWhenCondition : entry.getConditions())
			{
				if(ktWhenCondition instanceof KtWhenConditionWithExpression)
				{
					GeneratedElement inner = convertNonnull(((KtWhenConditionWithExpression) ktWhenCondition).getExpression());

					MethodCallExpression equalsCall = new MethodCallExpression(new StaticTypeQualifiedExpression(TypeName.get(Objects.class), "equals"), Arrays.asList(subjectExpression, inner));

					ifParts.add(Couple.of(equalsCall, whenExpr));
				}
				else
				{
					ifParts.add(Couple.of(convertNonnull(ktWhenCondition), whenExpr));
				}
			}

			if(elseKeyword != null)
			{
				elseItem = whenExpr;
			}
		}

		List<Couple<GeneratedElement>> reverse = ContainerUtil.reverse(ifParts);

		IfStatement prevStatement = null;

		for(Couple<GeneratedElement> ifPart : reverse)
		{
			GeneratedElement elsePart = prevStatement == null ? elseItem : prevStatement;

			IfStatement ifStatement = new IfStatement(ifPart.getFirst(), ifPart.getSecond(), elsePart);

			prevStatement = ifStatement;
		}

		myGeneratedElement = prevStatement;
	}

	@Override
	public void visitWhileExpression(KtWhileExpression expression)
	{
		GeneratedElement cond = convertNonnull(expression.getCondition());

		KtExpression body = expression.getBody();

		myGeneratedElement = new WhileStatement(cond, convertNonnull(body));
	}

	@Override
	public void visitForExpression(KtForExpression expression)
	{
		// TODO wrap to for() statement

		KtParameter loopParameter = expression.getLoopParameter();

		@NotNull GeneratedElement block = convertNonnull(expression.getBody());

		BindingContext context = ResolutionUtils.analyze(loopParameter);

		@NotNull GeneratedElement range = convertNonnull(expression.getLoopRange());

		LocalVariableDescriptor descriptor = (LocalVariableDescriptor) context.get(BindingContext.DECLARATION_TO_DESCRIPTOR, loopParameter);

		@NotNull TypeName typeName = TypeConverter.convertKotlinType(descriptor.getType());

		myGeneratedElement = new ForEachStatement(typeName, descriptor.getName().asString(), range, block);
	}

	@Override
	public void visitBlockExpression(KtBlockExpression expression)
	{
		List<KtExpression> statements = expression.getStatements();

		List<GeneratedElement> generatedElements = new ArrayList<>();
		for(KtExpression statement : statements)
		{
			GeneratedElement generatedElement = convertNonnull(statement);

			generatedElements.add(generatedElement);
		}

		myGeneratedElement = new BlockStatement(generatedElements);
	}

	@Override
	public void visitContinueExpression(KtContinueExpression expression)
	{
		myGeneratedElement = new ContinueStatement();
	}
}
