/** -*- mode:java; tab-width: 4 -*-
 * This file is part of Erjang - A JVM-based Erlang VM
 *
 * Copyright (c) 2010 by Trifork
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 **/

package erjang.beam.interpreter;

import java.util.List;
import java.util.ArrayList;
import java.util.HashMap;

import erjang.EModule;
import erjang.EModuleManager;
import erjang.EFun;
import erjang.FunID;
import erjang.EModuleClassLoader;

import erjang.ERT;
import erjang.ErlangError;
import erjang.ErlangException;

import erjang.EObject;
import erjang.EAtom;
import erjang.ESeq;
import erjang.ETuple3;
import erjang.EPseudoTerm;

import erjang.beam.ModuleVisitor;
import erjang.beam.FunctionVisitor;

import erjang.beam.repr.Insn;
import erjang.beam.repr.Operands;
import erjang.beam.repr.ExtFun;
import erjang.beam.repr.FunctionInfo;

import kilim.Pausable;

public class AbstractInterpreter {
	public static abstract class Encoder implements ModuleVisitor {
		private EAtom moduleName;
		final HashMap<Integer,Insn> insn_start = new HashMap<Integer,Insn>();
		final ArrayList<Short>     code      = new ArrayList<Short>();
		final ArrayList<EObject>   consts    = new ArrayList<EObject>();
		final ArrayList<ValueJumpTable> value_jump_tables = new ArrayList<ValueJumpTable>();
		final ArrayList<ArityJumpTable> arity_jump_tables = new ArrayList<ArityJumpTable>();
		final HashMap<EObject,Integer>	const_map = new HashMap<EObject,Integer>();
		final HashMap<Integer,Integer>	label_map = new HashMap<Integer,Integer>();
		final ArrayList<Backpatch>	    backpatches = new ArrayList<Backpatch>();
		final HashMap<FunID,Integer>	ext_fun_map = new HashMap<FunID,Integer>();
		final ArrayList<FunID>	imports = new ArrayList<FunID>();
		final ArrayList<FunctionInfo>	raw_exports = new ArrayList<FunctionInfo>();

		public void visitModule(EAtom name) {
			System.err.println("Interpreter.Encoder| doing module "+name);
			this.moduleName = name;
		}

		public void visitExport(EAtom fun, int arity, int entryLabel) {
			raw_exports.add(new FunctionInfo(moduleName, fun, arity, entryLabel));
		}

		public void visitAttribute(EAtom att, EObject value) {}

		public void visitEnd() {
			System.err.println("Interpreter code for module '"+moduleName+"':");

			for (Backpatch bp : backpatches) {
				bp.patch(label_map.get(bp.label));
			}

			for (int i=0; i<code.size(); i++) {
				Insn insn = insn_start.get(i);
				System.err.println((insn!=null? "*" : " ") + i +
								   ": " + code.get(i) +
								   (insn!=null ? ("\t"+insn.toSymbolic().toString()) : ""));
			}
		}

		public EModule toEModule() {
			short[] codeArray = toShortArray(code);
			EObject[] constArray = consts.toArray(new EObject[consts.size()]);
			ValueJumpTable[] valueJumpTableArray = value_jump_tables.toArray(new ValueJumpTable[value_jump_tables.size()]);
			ArityJumpTable[] arityJumpTableArray = arity_jump_tables.toArray(new ArityJumpTable[arity_jump_tables.size()]);
			List<FunIDWithEntry> exports = convertExports(raw_exports);
			System.err.println("INT| Constructing module for "+moduleName.getName());
			return makeModule(moduleName.getName(),
							  codeArray, constArray,
							  valueJumpTableArray, arityJumpTableArray,
							  exports, imports);
		}

		protected abstract EModule makeModule(String name,
											  short[] code, EObject[] consts,
											  ValueJumpTable[] value_jump_tables,
											  ArityJumpTable[] arity_jump_tables,
											  List<FunIDWithEntry> exports, List<FunID> imports);

		static short[] toShortArray(List<Short> org) {
			int len = org.size();
			short[] res = new short[len];
			for (int i=0; i<len; i++) res[i] = org.get(i);
			return res;
		}

		List<FunIDWithEntry> convertExports(List<FunctionInfo> org) {
			List<FunIDWithEntry> res = new ArrayList(org.size());
			for (FunctionInfo fi : org) {
				res.add(new FunIDWithEntry(fi.mod, fi.fun, fi.arity,
										   label_map.get(fi.label)));
			}
			return res;
		}


		public abstract FunctionVisitor visitFunction(EAtom name, int arity, int startLabel);

		//---------- Utility functions for encoder: --------------------

		protected int codePos() {return code.size();}

		protected void emit(short val) {code.add(val);}
		protected void emitAt(int pos, short val) {code.set(pos,val);}
		protected void nop(int code_pos) {
			code.remove(code_pos);
			code.subList(code_pos, code.size()).clear();
		}

		protected void emit(int intval) {
			short val = (short) intval;
			if (val!=intval) throw new Error("Value too large to be encoded: "+intval);
			code.add(val);
		}
		protected void emitAt(int pos, int intval) {
			short val = (short) intval;
			if (val!=intval) throw new Error("Value too large to be encoded: "+intval);
			code.set(pos, val);
		}

		protected int encodeLiteral(Operands.Literal lit) {
			EObject value = lit.literalValue();
			Integer index = const_map.get(value);
			if (index == null) {
				index = const_map.size();
				consts.add(value);
				const_map.put(value, index);
			}
			return index;
		}

		protected int encodeExtFun(ExtFun extfun) {
			FunID id = new FunID(extfun.mod, extfun.fun, extfun.arity);
			Integer index = ext_fun_map.get(id);
			if (index == null) {
				index = imports.size();
				imports.add(new FunID(extfun.mod, extfun.fun, extfun.arity));
				ext_fun_map.put(id, index);
			}
			return index;
		}

		protected int encodeValueJumpTable(Operands.SelectList jumpTable) {
			final ValueJumpTable table = new ValueJumpTable();
			for (int i=0; i<jumpTable.size(); i++) {
				final EObject value = ((Operands.Literal)jumpTable.getValue(i)).literalValue();
				int label = jumpTable.getLabel(i).nr;
				table.put(value, -12347);
				backpatches.add(new Backpatch(label) {
					public String toString() {return "Backpatch<value jump table "+label+">";}
					public void patch(int labelOffset) {
						table.put(value, labelOffset);
					}});
			}
			int index = value_jump_tables.size();
			value_jump_tables.add(table);
			return index;
		}

		protected int encodeArityJumpTable(Operands.SelectList jumpTable) {
			final ArityJumpTable table = new ArityJumpTable();
			for (int i=0; i<jumpTable.size(); i++) {
				final int arity = ((Operands.CodeInt)jumpTable.getValue(i)).value;
				int label = jumpTable.getLabel(i).nr;
				table.put(arity, -12346);
				backpatches.add(new Backpatch(label) {
					public String toString() {return "Backpatch<arity jump table "+label+">";}
					public void patch(int labelOffset) {
						table.put(arity, labelOffset);
					}});
			}
			int index = arity_jump_tables.size();
			arity_jump_tables.add(table);
			return index;
		}

		protected int encodeLabel(int label) {
			if (label_map.containsKey(label)) {
				return label_map.get(label);
			} else {
				if (label!=0) {
					final int codePos = codePos();
					backpatches.add(new Backpatch(label) {
						public String toString() {return "Backpatch<encoded label "+label+" @ "+codePos+">";}
						public void patch(int labelOffset) {
							emitAt(codePos, labelOffset);
						}});
				}
				return -12348;
			}
		}

		protected void registerLabel(int beamLabel) {
			assert(! label_map.containsKey(beamLabel));
			label_map.put(beamLabel, codePos());
		}
	}

    public static abstract class Module extends EModule {
		final protected String name;
        final protected ClassLoader module_class_loader;

		protected Module(String name, boolean delay_setup) {
			super(delay_setup);
			this.name = name;
			this.module_class_loader = new EModuleClassLoader(null);
		}

        public ClassLoader getModuleClassLoader() {
   	        return module_class_loader;
        }

		public String module_name() {
			return name;
		}

	}

	//---------- Utility functions for interpreter: --------------------

	protected static EObject[] ensureCapacity(EObject[] array, int atLeast) {
		if (atLeast >= array.length) {
			EObject[] tmp = new EObject[(atLeast)*3/2];
			System.arraycopy(array,0, tmp,0, array.length);
			array = tmp;
		}
		return array;
	}

	protected static EObject[] ensureCapacity(EObject[] array, int atLeast, int keep) {
		if (atLeast >= array.length) {
			EObject[] tmp = new EObject[(atLeast)*3/2];
			System.arraycopy(array,0, tmp,0, keep);
			array = tmp;
		}
		return array;
	}

	public static ESeq xregsSeq(EObject[] reg, int arity) {
		ESeq res = ERT.NIL;
		for (int i=arity-1; i>=0; i--) {
			res = res.cons(reg[i]);
		}
		return res;
	}

	public static EObject[] xregsArray(EObject[] reg, int arity) {
		EObject[] res = new EObject[arity];
		System.arraycopy(reg,0, res,0, arity);
		return res;
	}

	public static int nofailLabel() {
		throw new ErlangError(EAtom.intern("nofail_failed"));
	}

	static class FunIDWithEntry extends FunID {
		final int start_pc;

		public FunIDWithEntry(EAtom mod, EAtom fun, int arity, int start_pc) {
			super(mod, fun, arity);
			this.start_pc = start_pc;
		}
	}

	static class ValueJumpTable extends HashMap<EObject,Integer> {
		public int lookup(EObject key, int defaultLabel) {
			Integer lbl = get(key);
			return (lbl != null) ? lbl.intValue() : defaultLabel;
		}
	}

	static class ArityJumpTable extends HashMap<Integer,Integer> {
		public int lookup(int key, int defaultLabel) {
			Integer lbl = get(key);
			return (lbl != null) ? lbl.intValue() : defaultLabel;
		}
	}

	static abstract class Backpatch {
		final int label;
		public Backpatch(int label) { this.label=label; }
		public abstract void patch(int labelOffset);
	}

	protected static class VectorFunBinder extends EModuleManager.FunctionBinder {
		final EFun[] vector;
		final FunID funID; // (Technically superfluous?)
		final int index;
		public VectorFunBinder(EFun[] vector, FunID funID, int index) {
			this.vector = vector;
			this.funID = funID;
			this.index = index;
		}

		public FunID getFunID() {
			return funID;
		}

		public void bind(EFun value) {
			vector[index] = value;
		}
	}

	static abstract class ExceptionHandlerStackElement extends EPseudoTerm {
		final int pc;
		final ExceptionHandlerStackElement next;

		public ExceptionHandlerStackElement(int pc,
											ExceptionHandlerStackElement next)
		{
			this.pc = pc;
			this.next = next;
		}

		public String toString() {
			return "ExhElem("+pc+","+next+")";
		}

		public abstract void catchAction(ErlangException e, EObject[] reg);
	}

	static class CatchExceptionHandler extends ExceptionHandlerStackElement {
		public CatchExceptionHandler(int pc, ExceptionHandlerStackElement next) {
			super(pc, next);
		}

		public void catchAction(ErlangException e, EObject[] reg) {
			reg[0] = e.getCatchValue();
		}
	}

	static class TryExceptionHandler extends ExceptionHandlerStackElement {
		public TryExceptionHandler(int pc, ExceptionHandlerStackElement next) {
			super(pc, next);
		}

		public void catchAction(ErlangException e, EObject[] reg) {
			ETuple3 tmp = e.getTryValue();
			reg[0] = tmp.elem1;
			reg[1] = tmp.elem2;
			reg[2] = tmp.elem3;
		}
	}
}
