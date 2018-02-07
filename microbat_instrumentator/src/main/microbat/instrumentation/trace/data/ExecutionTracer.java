package microbat.instrumentation.trace.data;

import java.util.HashMap;
import java.util.Map;

import microbat.model.BreakPoint;
import microbat.model.trace.Trace;
import microbat.model.trace.TraceNode;
import microbat.model.value.ArrayValue;
import microbat.model.value.PrimitiveValue;
import microbat.model.value.ReferenceValue;
import microbat.model.value.StringValue;
import microbat.model.value.VarValue;
import microbat.model.variable.ArrayElementVar;
import microbat.model.variable.FieldVar;
import microbat.model.variable.LocalVar;
import microbat.model.variable.Variable;
import microbat.model.variable.VirtualVar;
import microbat.util.PrimitiveUtils;
import sav.common.core.utils.SignatureUtils;
import sav.common.core.utils.StringUtils;
import sav.strategies.dto.AppJavaClassPath;

public class ExecutionTracer implements IExecutionTracer {
	private static Map<Long, ExecutionTracer> rtStores;
	private static long mainThreadId = -1;
	static {
		rtStores = new HashMap<>();
	}
	private Trace trace;

	private TraceNode currentNode;
	private BreakPoint methodEntry;
	/*
	 * For exclusive case,  
	 */
	private boolean exclusive; //current method is in exclude list.
	private InvokingTrack invokeTrack = new EmptyInvokingTrack();
	private MethodCallStack methodCallStack;
	private Locker locker = new Locker();

	public ExecutionTracer() {
		methodCallStack = new MethodCallStack();
		AppJavaClassPath appJavaClassPath = new AppJavaClassPath();
		trace = new Trace(appJavaClassPath);
	}

	/* TODO: Set aliasVarId*/
	private VarValue appendVarValue(Object value, Variable var, VarValue parent) {
		boolean isRoot = (parent == null);
		VarValue varValue = null;
		if (PrimitiveUtils.isString(var.getType())) {
			return new StringValue(getStringValue(value), isRoot, var);
		} else if (PrimitiveUtils.isPrimitiveType(var.getType())) {
			return new PrimitiveValue(getStringValue(value), isRoot, var);
		} else if(var.getType().endsWith("[]")) {
			/* array */
			ArrayValue arrVal = new ArrayValue(value == null, isRoot, var);
			arrVal.setComponentType(var.getType().substring(0, var.getType().length() - 2)); // 2 = "[]".length
			varValue = arrVal;
			/* TODO append children */
		} else {
			ReferenceValue refVal = new ReferenceValue(value == null, TraceUtils.getUniqueId(value), isRoot, var);
			varValue = refVal;
			/* TODO append children */
		}
		if (parent != null) {
			parent.linkAchild(varValue);
		}
		return varValue;
	}
	
	private String getStringValue(Object obj) {
		return StringUtils.toString(obj, null);
	}

	/* 
	 * Methods which are with prefix "_" are called in instrument code.
	 * =================================================================
	 * */
	public void enterMethod(String className, String methodName, int methodStartLine) {
		locker.lock();
		exclusive = exclusive || FilterChecker.isExclusive(className, methodName);
		// TODO-INSTR: declaringCompilationUnitName would not be correct here.
		methodEntry = new BreakPoint(className, className, methodName, methodStartLine);
		currentNode = null;
		invokeTrack.setBkp(methodEntry);
		locker.unLock();
	}

	public void exitMethod(int line) {
		locker.lock();
		OnWorkingMethod onWorkingMethod = methodCallStack.safePop();
		if (onWorkingMethod != null) {
			this.exclusive = onWorkingMethod.isExclusive();
			this.methodEntry = onWorkingMethod.getMethodEntry();
			this.currentNode = onWorkingMethod.getCurrentNode();
			this.invokeTrack = onWorkingMethod.getInvokeTrack();
		}
		locker.unLock();
	}

	@Override
	public void _hitInvoke(Object invokeObj, String invokeTypeSign, String methodName, Object[] params,
			String paramTypeSignsCode, String returnTypeSign, int line) {
		locker.lock();
		_hitLine(line);
		/* save current state */
		methodCallStack.push(currentNode, methodEntry, exclusive, invokeTrack);
		/* set up invokeTrack */
		invokeTrack = new InvokingTrack(invokeObj, invokeTypeSign, methodName, params,
				TraceUtils.parseArgTypes(paramTypeSignsCode));
		locker.unLock();
	}
	
	@Override
	public void _hitInvokeStatic(String invokeTypeSign, String methodName, Object[] params,
			String paramTypeSignsCode, String returnTypeSign, int line) {
		_hitLine(line);
	}
	
	/**
	 * @param line
	 * @param returnObj
	 * @param returnGeneralType (if type is object type -> this will be display of object type, not specific name 
	 */
	@Override
	public void _hitReturn(Object returnObj, String returnGeneralType, int line) {
		locker.lock();
		_hitLine(line);
		Variable returnVar = new VirtualVar(invokeTrack.getInvokeNodeId(), returnGeneralType);
		VarValue returnVal = appendVarValue(returnObj, returnVar, null);
		invokeTrack.setReturnValue(returnVal);
		exitMethod(line);
		locker.unLock();
	}
	
	@Override
	public void _hitVoidReturn(int line) {
		_hitLine(line);
		exitMethod(line);
	}

	@Override
	public void _hitLine(int line) {
		if (exclusive) {
			return;
		}
		locker.lock();
		if (currentNode != null && currentNode.getBreakPoint().getLineNumber() == line) {
			locker.unLock();
			return;
		}
		/* TODO LLT: in Breakpoint, we need to set methodName or signature? */
		BreakPoint bkp = new BreakPoint(methodEntry.getClassCanonicalName(),
				methodEntry.getDeclaringCompilationUnitName(), methodEntry.getMethodSign(), line);
		int order = trace.size() + 1;
		currentNode = new TraceNode(bkp, null, order, trace); // leave programState empty.
		trace.addTraceNode(currentNode);
		locker.unLock();
	}
	
	/**
	 * @param refValue
	 * @param fieldValue
	 * @param fieldTypeSign
	 * @param line
	 */
	@Override
	public void _writeField(Object refValue, Object fieldValue, String fieldName, String fieldTypeSign, int line) {
		locker.lock();
		_hitLine(line);
		String parentVarId = TraceUtils.getObjectVarId(refValue);
		String fieldVarId = Variable.concanateFieldVarID(parentVarId, fieldName);
		boolean invokeRelevant = invokeTrack.updateRelevant(parentVarId, fieldVarId);
		if (exclusive && !invokeRelevant) {
			locker.unLock();
			return;
		}
		Variable var = new FieldVar(false, fieldName, SignatureUtils.signatureToName(fieldTypeSign));
		var.setVarID(fieldVarId);
		VarValue value = appendVarValue(fieldValue, var, null);
		addRWriteValue(value, true);
		if (invokeRelevant) {
			invokeTrack.addWrittenValue(value);
		}
		locker.unLock();
	}

	private void addRWriteValue(VarValue value, boolean isWrittenVar) {
		if (currentNode == null) {
			return;
		}
		if (isWrittenVar) {
			currentNode.addWrittenVariable(value);
		} else {
			currentNode.addReadVariable(value);
		}
	}
	
	/**
	 * @param fieldValue
	 * @param refType
	 * @param fieldName
	 * @param fieldTypeSign
	 * @param line
	 * TODO LLT: handle relevant?
	 */
	@Override
	public void _writeStaticField(Object fieldValue, String refType, String fieldName, String fieldType, int line) {
		if (exclusive) {
			return;
		}
		locker.lock();
		_hitLine(line);
		Variable var = new FieldVar(false, fieldName, fieldType);
		var.setVarID(Variable.concanateFieldVarID(refType, fieldName));
		VarValue value = appendVarValue(fieldValue, var, null);
		addRWriteValue(value, true);
		locker.unLock();
	}

	/**
	 * @param refValue
	 * @param fieldValue
	 * @param fieldType
	 * @param line
	 */
	@Override
	public void _readField(Object refValue, Object fieldValue, String fieldName, String fieldType, int line) {
		_hitLine(line);
		locker.lock();
		String parentVarId = TraceUtils.getObjectVarId(refValue);
		String fieldVarId = TraceUtils.getFieldVarId(parentVarId, fieldName, fieldType, fieldValue);
		invokeTrack.updateRelevant(parentVarId, fieldVarId);
		if (exclusive) {
			return;
		}
		Variable var = new FieldVar(false, fieldName, fieldType);
		var.setVarID(fieldVarId);
		VarValue value = appendVarValue(fieldValue, var, null);
		addRWriteValue(value, false);
		locker.unLock();
	}

	/**
	 * @param fieldValue
	 * @param refType
	 * @param fieldName
	 * @param fieldTypeSign
	 * @param line
	 */
	@Override
	public void _readStaticField(Object fieldValue, String refType, String fieldName, String fieldTypeSign, int line) {
		if (exclusive) {
			return;
		}
		locker.lock();
		_hitLine(line);
		Variable var = new FieldVar(true, fieldName, SignatureUtils.signatureToName(fieldTypeSign));
		var.setVarID(Variable.concanateFieldVarID(refType, fieldName));
		VarValue value = appendVarValue(fieldValue, var, null);
		addRWriteValue(value, false);;
		locker.unLock();
	}
	
	/**
	 * @param varValue
	 * @param varName
	 * @param varType
	 * @param line
	 * @param bcLocalVarIdx
	 */
	@Override
	public void _writeLocalVar(Object varValue, String varName, String varType, int line, int bcLocalVarIdx,
			int varScopeStartLine, int varScopeEndLine) {
		if (exclusive) {
			return;
		}
		_hitLine(line);
		locker.lock();
		Variable var = new LocalVar(varName, varType, methodEntry.getClassCanonicalName(), line);
		var.setVarID(Variable.concanateLocalVarID(methodEntry.getClassCanonicalName(), varName, varScopeStartLine,
				varScopeEndLine));
		VarValue value = appendVarValue(varValue, var, null);
		addRWriteValue(value, true);
		locker.unLock();
	}
	
	/**
	 * @param value
	 * @param varName
	 * @param varType
	 * @param line
	 * @param bcLocalVarIdx
	 */
	@Override
	public void _readLocalVar(Object varValue, String varName, String varType, int line, int bcLocalVarIdx,
			int varScopeStartLine, int varScopeEndLine) {
		_hitLine(line);
		locker.lock();
		Variable var = new LocalVar(varName, varType, methodEntry.getClassCanonicalName(), line);
		var.setVarID(Variable.concanateLocalVarID(methodEntry.getClassCanonicalName(), varName, varScopeStartLine,
				varScopeEndLine));
		VarValue value = appendVarValue(varValue, var, null);
		addRWriteValue(value, false);
		locker.unLock();
	}
	
	/**
	 * @param arrayRef
	 * @param index
	 * @param eleValue
	 * @param elementType
	 * @param line
	 */
	@Override
	public void _readArrayElementVar(Object arrayRef, int index, Object eleValue, String elementType, int line) {
		_hitLine(line);
		locker.lock();
		String parentVarId = TraceUtils.getObjectVarId(arrayRef);
		String arrEleVarId = TraceUtils.getArrayElementVarId(parentVarId, index, elementType, eleValue);
		invokeTrack.updateRelevant(parentVarId, arrEleVarId);
		if (exclusive) {
			locker.unLock();
			return;
		}
		addArrayElementVarValue(arrayRef, index, eleValue, elementType, line, false);
		locker.unLock();
	}
	
	/**
	 * 
	 * @param arrayRef
	 * @param index
	 * @param eleValue
	 * @param elementType
	 * @param line
	 */
	@Override
	public void _writeArrayElementVar(Object arrayRef, int index, Object eleValue, String elementType, int line) {
		_hitLine(line);
		locker.lock();
		String parentVarId = TraceUtils.getObjectVarId(arrayRef);
		String arrEleVarId = TraceUtils.getArrayElementVarId(parentVarId, index, elementType, eleValue);
		boolean involeRelevant = invokeTrack.updateRelevant(parentVarId, arrEleVarId);
		if (exclusive && !involeRelevant) {
			locker.unLock();
			return;
		}
		VarValue value = addArrayElementVarValue(arrayRef, index, eleValue, elementType, line, true);
		invokeTrack.addWrittenValue(value);
		locker.unLock();
	}
	
	private VarValue addArrayElementVarValue(Object arrayRef, int index, Object eleValue, String elementType, int line,
			boolean write) {
		String name = new StringBuilder(TraceUtils.getObjectVarId(arrayRef)).append("[").append(index).append("]").toString();
		String eleType = SignatureUtils.signatureToName(arrayRef.getClass().getName());
		Variable var = new ArrayElementVar(name, eleType, null);
		VarValue value = appendVarValue(eleValue, var, null);
		addRWriteValue(value, write);
		return value;
	}
	
	public void tryTracer(Object refValue, Object fieldValue) {
		// TODO LLT: do nothing, JUST FOR TEST. TO REMOVE.
	}

	private static final Locker gLocker = new Locker();
	public synchronized static IExecutionTracer _getTracer(String className, String methodName, int methodStartLine) {
		if (gLocker.lock()) {
			return EmptyExecutionTracer.getInstance();
		}
		long threadId = Thread.currentThread().getId();
		if (mainThreadId < 0) {
			mainThreadId = threadId;
		}
		ExecutionTracer tracer = getTracer(threadId);
		if (tracer.locker.isLock()) {
			gLocker.unLock();
			return EmptyExecutionTracer.getInstance();
		}
		tracer.enterMethod(className.replace("/", "."), methodName, methodStartLine);
		gLocker.unLock();
		return tracer;
	}

	private static ExecutionTracer getTracer(long threadId) {
		ExecutionTracer store = rtStores.get(threadId);
		if (store == null) {
			store = new ExecutionTracer();
			rtStores.put(threadId, store);
		}
		return store;
	}
	
	public static Map<Long, ExecutionTracer> getRtStores() {
		return rtStores;
	}
	
	public synchronized static IExecutionTracer getMainThreadStore() {
		return getTracer(mainThreadId);
	}
	
	private static boolean shutdown;
	public static void shutdown() {
		gLocker.lock();
		shutdown = true;
	}
	
	public static boolean isShutdown() {
		return shutdown;
	}
	
	public Trace getTrace() {
		return trace;
	}
	
	private static class Locker {
		boolean tracing;
		
		/**
		 * return true if already lock
		 * false if not locked
		 * */
		public boolean lock() {
			if (tracing) {
				return true;
			}
			tracing = true;
			return false;
		}
		
		public void unLock() {
			tracing = false;
		}
		
		public boolean isLock() {
			return tracing;
		}
	}
}
