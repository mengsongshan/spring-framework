/*
 * Copyright 2002-2024 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.expression.spel.ast;

import java.lang.reflect.Modifier;
import java.util.function.Supplier;

import org.springframework.asm.MethodVisitor;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.EvaluationException;
import org.springframework.expression.TypedValue;
import org.springframework.expression.spel.CodeFlow;
import org.springframework.expression.spel.ExpressionState;
import org.springframework.expression.spel.SpelEvaluationException;
import org.springframework.lang.Nullable;

/**
 * Represents a variable reference &mdash; for example, {@code #root}, {@code #this},
 * {@code #someVar}, etc.
 *
 * @author Andy Clement
 * @author Sam Brannen
 * @since 3.0
 */
public class VariableReference extends SpelNodeImpl {

	/** Currently active context object. */
	private static final String THIS = "this";

	/** Root context object. */
	private static final String ROOT = "root";


	private final String name;


	public VariableReference(String variableName, int startPos, int endPos) {
		super(startPos, endPos);
		this.name = variableName;
	}


	@Override
	public ValueRef getValueRef(ExpressionState state) throws SpelEvaluationException {
		if (THIS.equals(this.name)) {
			return new ValueRef.TypedValueHolderValueRef(state.getActiveContextObject(), this);
		}
		if (ROOT.equals(this.name)) {
			return new ValueRef.TypedValueHolderValueRef(state.getRootContextObject(), this);
		}
		TypedValue result = state.lookupVariable(this.name);
		// A null value in the returned VariableRef will mean either the value was
		// null or the variable was not found.
		return new VariableRef(this.name, result, state.getEvaluationContext());
	}

	@Override
	public TypedValue getValueInternal(ExpressionState state) throws SpelEvaluationException {
		TypedValue result;
		if (THIS.equals(this.name)) {
			result = state.getActiveContextObject();
			// If the active context object (#this) is not the root context object (#root),
			// that means that #this is being evaluated within a nested scope (for example,
			// collection selection or collection project), which is not a compilable
			// expression, so we return the result without setting the exit type descriptor.
			if (result != state.getRootContextObject()) {
				return result;
			}
		}
		else if (ROOT.equals(this.name)) {
			result = state.getRootContextObject();
		}
		else {
			result = state.lookupVariable(this.name);
		}
		setExitTypeDescriptor(result.getValue());

		// A null value in the returned TypedValue will mean either the value was
		// null or the variable was not found.
		return result;
	}

	/**
	 * Set the exit type descriptor for the supplied value.
	 * <p>If the value is {@code null}, we set the exit type descriptor to
	 * {@link Object}.
	 * <p>If the value's type is not public, {@link #generateCode} would insert
	 * a checkcast to the non-public type in the generated byte code which would
	 * result in an {@link IllegalAccessError} when the compiled byte code is
	 * invoked. Thus, as a preventative measure, we set the exit type descriptor
	 * to {@code Object} in such cases. If resorting to {@code Object} is not
	 * sufficient, we could consider traversing the hierarchy to find the first
	 * public type.
	 */
	private void setExitTypeDescriptor(@Nullable Object value) {
		if (value == null || !Modifier.isPublic(value.getClass().getModifiers())) {
			this.exitTypeDescriptor = "Ljava/lang/Object";
		}
		else {
			this.exitTypeDescriptor = CodeFlow.toDescriptorFromObject(value);
		}
	}

	@Override
	public TypedValue setValueInternal(ExpressionState state, Supplier<TypedValue> valueSupplier)
			throws EvaluationException {

		return state.assignVariable(this.name, valueSupplier);
	}

	@Override
	public String toStringAST() {
		return "#" + this.name;
	}

	@Override
	public boolean isWritable(ExpressionState expressionState) throws SpelEvaluationException {
		return !(THIS.equals(this.name) || ROOT.equals(this.name));
	}

	@Override
	public boolean isCompilable() {
		return (this.exitTypeDescriptor != null);
	}

	@Override
	public void generateCode(MethodVisitor mv, CodeFlow cf) {
		if (THIS.equals(this.name) || ROOT.equals(this.name)) {
			mv.visitVarInsn(ALOAD, 1);
		}
		else {
			mv.visitVarInsn(ALOAD, 2);
			mv.visitLdcInsn(this.name);
			mv.visitMethodInsn(INVOKEINTERFACE, "org/springframework/expression/EvaluationContext",
					"lookupVariable", "(Ljava/lang/String;)Ljava/lang/Object;", true);
		}
		CodeFlow.insertCheckCast(mv, this.exitTypeDescriptor);
		cf.pushDescriptor(this.exitTypeDescriptor);
	}


	private static class VariableRef implements ValueRef {

		private final String name;

		private final TypedValue value;

		private final EvaluationContext evaluationContext;

		public VariableRef(String name, TypedValue value, EvaluationContext evaluationContext) {
			this.name = name;
			this.value = value;
			this.evaluationContext = evaluationContext;
		}

		@Override
		public TypedValue getValue() {
			return this.value;
		}

		@Override
		public void setValue(@Nullable Object newValue) {
			this.evaluationContext.setVariable(this.name, newValue);
		}

		@Override
		public boolean isWritable() {
			return true;
		}
	}

}
