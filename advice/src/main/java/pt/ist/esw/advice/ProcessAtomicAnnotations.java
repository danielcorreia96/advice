/*
 * AtomicAnnotation
 * Copyright (C) 2012 INESC-ID Software Engineering Group
 * http://www.esw.inesc-id.pt
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA  02110-1301  USA
 *
 * Author's contact:
 * INESC-ID Software Engineering Group
 * Rua Alves Redol 9
 * 1000 - 029 Lisboa
 * Portugal
 */
package pt.ist.esw.advice;

import java.io.*;
import java.util.*;

import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

import static org.objectweb.asm.Opcodes.*;

public class ProcessAtomicAnnotations {
    private static final Type ATOMIC = Type.getType(Atomic.class);
    private static final Type ATOMIC_CONTEXT = Type.getType(Advice.class);
    private static final Type ATOMIC_INSTANCE = Type.getObjectType(GenerateAtomicInstance.ATOMIC_INSTANCE);
    private static final Map<String,Object> ATOMIC_ELEMENTS;
    private static final List<FieldNode> ATOMIC_FIELDS;
    private static final String ATOMIC_INSTANCE_CTOR_DESC;

    static {
        Map<String,Object> atomicElements = new HashMap<String,Object>();
        for (java.lang.reflect.Method element : Atomic.class.getDeclaredMethods()) {
            Object defaultValue = element.getDefaultValue();
            if (defaultValue instanceof Class) {
                defaultValue = Type.getType((Class<?>) defaultValue);
            }
            atomicElements.put(element.getName(), defaultValue);
        }
        ATOMIC_ELEMENTS = Collections.unmodifiableMap(atomicElements);

        try {
            InputStream is = Thread.currentThread().getContextClassLoader()
                .getResourceAsStream(ATOMIC_INSTANCE.getInternalName() + ".class");
            ClassReader cr = new ClassReader(is);
            ClassNode cNode = new ClassNode();
            cr.accept(cNode, 0);
            ATOMIC_FIELDS = cNode.fields != null ? cNode.fields : Collections.<FieldNode>emptyList();

            StringBuffer ctorDescriptor = new StringBuffer("(");
            for (FieldNode field : ATOMIC_FIELDS) ctorDescriptor.append(field.desc);
            ctorDescriptor.append(")V");
            ATOMIC_INSTANCE_CTOR_DESC = ctorDescriptor.toString();
        } catch (IOException e) {
            throw new RuntimeException("Error opening AtomicInstance class. Have you run GenerateAtomicInstance?", e);
        }
    }

    private ProcessAtomicAnnotations() {}
    
    public static void main (final String args[]) throws Exception {
        for (String file : args) {
            ProcessAtomicAnnotations.processFile(new File(file));
        }
    }

    public static void processFiles(File [] files) {
        for (File file : files) {
            processFile(file);
        }
    }

    public static void processFile(File file) {
        if (file.isDirectory()) {
            for (File subFile : file.listFiles()) {
                processFile(subFile);
            }
        } else {
            String fileName = file.getName();
            if (fileName.toLowerCase().endsWith(".class")) {
                processClassFile(file);
            }
        }
    }

    protected static void processClassFile(File classFile) {
        InputStream is = null;

        try {
            // get an input stream to read the bytecode of the class
            is = new FileInputStream(classFile);
            ClassReader cr = new ClassReader(is);
            ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);

            ClassVisitor cv = cw;
            // Add here other visitors to run AFTER the AtomicMethodTransformer
            cv = new AtomicMethodTransformer(cv, classFile);
            // Add here other visitors to run BEFORE the AtomicMethodTransformer

            cr.accept(cv, 0);
            writeClassFile(classFile, cw.toByteArray());
        } catch (IOException e) {
            throw new RuntimeException("Error processing class file " + classFile.getPath(), e);
        } finally {
            if (is != null) {
                try {
                    is.close();
                } catch (IOException e) { }
            }
        }
    }

    protected static void writeClassFile(File classFile, byte[] bytecode) {
        FileOutputStream fos = null;
        try {
            fos = new FileOutputStream(classFile);
            fos.write(bytecode);
        } catch (IOException e) {
            throw new RuntimeException("Couldn't write class file" + classFile.getPath(), e);
        } finally {
            if (fos != null) {
                try {
                    fos.close();
                } catch (IOException e) { }
            }
        }
    }

    static class AtomicMethodTransformer extends ClassVisitor {
        private final List<MethodNode> methods = new ArrayList<MethodNode>();
        private final List<String> atomicMethodNames = new ArrayList<String>();
        private final MethodNode atomicClInit;
        private final File classFile;

        private String className;

        public AtomicMethodTransformer(ClassVisitor cv, File originalClassFile) {
            super(ASM4, cv);

            classFile = originalClassFile;

            atomicClInit = new MethodNode(ACC_STATIC, "<clinit>", "()V", null, null);
            atomicClInit.visitCode();
        }

        @Override
        public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
            className = name;
            cv.visit(version, access, name, signature, superName, interfaces);
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
            // Use a MethodNode to represent the method
            MethodNode mn = new MethodNode(access, name, desc, signature, exceptions);
            methods.add(mn);
            return mn;
        }

        @Override
        public void visitEnd() {
            MethodNode clInit = null;
            boolean hasAtomic = false;
            for (MethodNode mn : methods) {
                if (mn.name.equals("<clinit>")) {
                    clInit = mn;
                    continue;
                }

                if (mn.invisibleAnnotations != null) {
                    for (AnnotationNode an : mn.invisibleAnnotations) {
                        if (an.desc.equals(ATOMIC.getDescriptor())) {
                            //System.out.println("Method " + mn.name + " is tagged with @Atomic");
                            hasAtomic = true;
                            // Create new transactified method
                            transactify(mn, an);
                            break;
                        }
                    }
                }
                // Visit method, so it will be present on the output class
                mn.accept(cv);
            }

            if (hasAtomic) {
                // Insert <clinit> into class
                if (clInit != null) {
                    // Merge existing clinit with our additions
                    clInit.instructions.accept(atomicClInit);
                } else {
                    atomicClInit.visitInsn(RETURN);
                }
                atomicClInit.visitMaxs(0, 0);
                atomicClInit.visitEnd();
                atomicClInit.accept(cv);
            } else {
                // Preserve existing <clinit>
                if (clInit != null) clInit.accept(cv);
            }

            cv.visitEnd();
        }

        /**
          * To transactify method add, part of the class Xpto, and with signature
          * @Atomic @SomethingElse public long add(Object o, int i)
          * we generate the following code:
          *
          * public static [final] Advice context$add = Advice.newContext();
          *
          * @SomethingElse
          * public long add(Object o, int i) {
          *     static final class atomicannotation$callable$add implements Callable {
          *         Xpto arg0;
          *         Object arg1;
          *         int arg2;
          *
          *         atomicannotation$callable$add(Xpto arg0, Object arg1, int arg2) {
          *             this.arg0 = arg0;
          *             this.arg1 = arg1;
          *             this.arg2 = arg2;
          *         }
          *
          *         public Object call() {
          *             return Xpto.atomic$add(arg0, arg1, arg2);
          *         }
          *     }
          *     return context$add.doTransactionally(new atomicannotation$callable$add(this, o, i));
          * }
          *
          * synthetic static long atomic$add(Xpto this, Object o, int i) {
          *     // original method
          * }
          *
          * Note that any annotations from the original method are removed from the atomic$ version.
          **/
        private void transactify(MethodNode mn, AnnotationNode atomicAnnotation) {
            // Mangle name if there are multiple atomic methods with the same name
            String methodName = getMethodName(mn.name);
            // Name for context field
            String fieldName = "context$" + methodName;
            // Name for callable class
            String callableClass = className + "$atomicannotation$callable$" + methodName;

            // Generate new method which will invoke the context with the Callable
            MethodVisitor atomicMethod = cv.visitMethod(mn.access, mn.name, mn.desc, mn.signature, mn.exceptions.toArray(new String[0]));

            // Remove @Atomic annotation
            mn.invisibleAnnotations.remove(atomicAnnotation);
            // Copy other annotations from the original method to the newly created method
            for (AnnotationNode an : mn.invisibleAnnotations) {
                an.accept(atomicMethod.visitAnnotation(an.desc, false));
            }
            if (mn.visibleAnnotations != null) {
                for (AnnotationNode an : mn.visibleAnnotations) {
                    an.accept(atomicMethod.visitAnnotation(an.desc, true));
                }
            }

            // Create field to save context
            cv.visitField(ACC_PUBLIC | ACC_STATIC | ACC_FINAL, fieldName, ATOMIC_CONTEXT.getDescriptor(), null, null);

            // Add code to clinit to initialize the field
            // Add default parameters from @Atomic
            Map<String,Object> atomicElements = new HashMap<String,Object>(ATOMIC_ELEMENTS);
            // Copy parameters from method annotation
            if (atomicAnnotation.values != null) {
                Iterator<Object> it = atomicAnnotation.values.iterator();
                while (it.hasNext()) {
                    // ASM stores annotation values as String1, Object1, String2, Object2, ... in the values list
                    atomicElements.put((String) it.next(), it.next());
                }
            }
            // Push @Atomic parameters on the stack and create AtomicInstance
            atomicClInit.visitTypeInsn(NEW, ATOMIC_INSTANCE.getInternalName());
            atomicClInit.visitInsn(DUP);
            for (FieldNode field : ATOMIC_FIELDS) {
                atomicClInit.visitLdcInsn(atomicElements.get(field.name));
            }
            atomicClInit.visitMethodInsn(INVOKESPECIAL, ATOMIC_INSTANCE.getInternalName(), "<init>", ATOMIC_INSTANCE_CTOR_DESC);
            // Obtain atomic context for this method
            atomicClInit.visitMethodInsn(INVOKESTATIC, ((Type) atomicElements.get("contextFactory")).getInternalName(), "newContext", "(" + ATOMIC.getDescriptor() + ")" + ATOMIC_CONTEXT.getDescriptor());
            atomicClInit.visitFieldInsn(PUTSTATIC, className, fieldName, ATOMIC_CONTEXT.getDescriptor());

            // Repurpose original method
            modifyOriginalMethod(mn);

            // Generate replacement method
            generateMethodCode(mn, atomicMethod, fieldName, callableClass);

            // Generate callable class
            generateCallable(callableClass, mn);
        }

        private void modifyOriginalMethod(MethodNode mn) {
            // Rename original method
            mn.name = "atomic$" + mn.name;
            // Remove annotations from original method
            mn.invisibleAnnotations = Collections.<AnnotationNode>emptyList();
            mn.visibleAnnotations = Collections.<AnnotationNode>emptyList();
            // Modify the access flags, setting the method as package protected, so that the callable can access it
            mn.access &= ~ACC_PRIVATE & ~ACC_PUBLIC;
            // Also mark it as synthetic, so Java tools ignore it
            mn.access |= ACC_SYNTHETIC;

            if (!isStatic(mn)) {
                // Convert original method to static method with instance as first argument
                // Note that the bytecode is still valid, as ALOAD 0 (an access to this) will still have
                // the same semantics
                mn.access |= ACC_STATIC;
                mn.desc = "(L" + className + ";" + mn.desc.substring(1);
        }
        }

        private void generateMethodCode(MethodNode mn, MethodVisitor mv, String fieldName, String callableClass) {
            mv.visitCode();
            mv.visitFieldInsn(GETSTATIC, className, fieldName, ATOMIC_CONTEXT.getDescriptor());
            mv.visitTypeInsn(NEW, callableClass);
            mv.visitInsn(DUP);

            int pos = 0;
            // Push arguments for original method on the stack
            for (Type t : Type.getArgumentTypes(mn.desc)) {
                mv.visitVarInsn(t.getOpcode(ILOAD), pos);
                pos += t.getSize();
            }
            mv.visitMethodInsn(INVOKESPECIAL, callableClass, "<init>", getCallableCtorDesc(mn));
            mv.visitMethodInsn(INVOKEINTERFACE, ATOMIC_CONTEXT.getInternalName(), "doTransactionally",
                    "(Ljava/util/concurrent/Callable;)Ljava/lang/Object;");

            // Return value
            Type returnType = Type.getReturnType(mn.desc);
            if (returnType.getSort() == Type.OBJECT || returnType.getSort() == Type.ARRAY) {
                mv.visitTypeInsn(CHECKCAST, returnType.getInternalName());
            } else if (isPrimitive(returnType)) {
                // Return is native, we have to unbox the value from the Advice
                boxUnwrap(returnType, mv);
            }
            mv.visitInsn(returnType.getOpcode(IRETURN));
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }

        private static boolean isStatic(MethodNode mn) {
            return (mn.access & ACC_STATIC) > 0;
        }

        private String getCallableCtorDesc(MethodNode mn) {
            return mn.desc.substring(0, mn.desc.indexOf(')') + 1) + 'V';
        }

        private String getMethodName(String methodName) {
            // Count number of atomic methods with same name
            int count = 0;
            for (String name : atomicMethodNames) {
                if (name.equals(methodName)) count++;
            }
            // Add another one
            atomicMethodNames.add(methodName);

            return methodName + (count > 0 ? "$" + count : "");
        }

        private void generateCallable(String callableClass, MethodNode mn) {
            Type returnType = Type.getReturnType(mn.desc);

            Type[] arguments = Type.getArgumentTypes(mn.desc);

            ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            cw.visit(V1_6, ACC_FINAL, callableClass, "Ljava/lang/Object;Ljava/util/concurrent/Callable<" +
                            (isPrimitive(returnType) ? toObject(returnType) :
                                (returnType.equals(Type.VOID_TYPE) ? Type.getObjectType("java/lang/Void") :
                                    returnType)).getDescriptor() + ">;",
                    "java/lang/Object", new String[] { "java/util/concurrent/Callable" });
            cw.visitSource("AtomicAnnotation Automatically Generated Class", null);

            // Create fields to hold arguments
            {
                int fieldPos = 0;
                for (Type t : arguments) {
                    cw.visitField(ACC_PRIVATE | ACC_FINAL, "arg" + (fieldPos++), t.getDescriptor(), null, null);
                }
            }

            // Create constructor
            {
                MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "<init>", getCallableCtorDesc(mn), null, null);
                mv.visitCode();
                mv.visitVarInsn(ALOAD, 0);
                mv.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V");
                int localsPos = 0;
                int fieldPos = 0;
                for (Type t : arguments) {
                        mv.visitVarInsn(ALOAD, 0);
                        mv.visitVarInsn(t.getOpcode(ILOAD), localsPos+1);
                        mv.visitFieldInsn(PUTFIELD, callableClass, "arg" + fieldPos++, t.getDescriptor());
                        localsPos += t.getSize();
                }
                mv.visitInsn(RETURN);
                mv.visitMaxs(0, 0);
                mv.visitEnd();
            }

            // Create call method
            {
                // Note: Usually when in Java you implement an interface with generics, such as Callable<Xpto>,
                //      javac generates a Xpto call() method and an Object call() tagged as "public bridge synthetic"
                //      that calls the previous one. Here, we generate the non-generic version immediately.
                MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "call", "()Ljava/lang/Object;", null, null);
                mv.visitCode();
                int fieldPos = 0;
                for (Type t : arguments) {
                        mv.visitVarInsn(ALOAD, 0);
                        mv.visitFieldInsn(GETFIELD, callableClass, "arg" + fieldPos++, t.getDescriptor());
                }
                mv.visitMethodInsn(INVOKESTATIC, className, mn.name, mn.desc);
                if (returnType.equals(Type.VOID_TYPE)) mv.visitInsn(ACONST_NULL);
                else if (isPrimitive(returnType)) boxWrap(returnType, mv);
                mv.visitInsn(ARETURN);
                mv.visitMaxs(0, 0);
                mv.visitEnd();
            }

            // Write the callable class file in the same directory as the original class file
            String callableFileName = callableClass.substring(Math.max(callableClass.lastIndexOf('/'), 0)) + ".class";
            writeClassFile(new File(classFile.getParent() + File.separatorChar + callableFileName), cw.toByteArray());
        }

        private static final Object[][] primitiveWrappers = new Object[][] {
            {"java/lang/Boolean", Type.BOOLEAN_TYPE}, {"java/lang/Byte", Type.BYTE_TYPE},
            {"java/lang/Character", Type.CHAR_TYPE}, {"java/lang/Short", Type.SHORT_TYPE},
            {"java/lang/Integer", Type.INT_TYPE}, {"java/lang/Long", Type.LONG_TYPE},
            {"java/lang/Float", Type.FLOAT_TYPE}, {"java/lang/Double", Type.DOUBLE_TYPE}
        };

        private static Type toObject(Type primitiveType) {
            for (Object[] map : primitiveWrappers) {
                if (primitiveType.equals(map[1])) return Type.getObjectType((String) map[0]);
            }
            throw new AssertionError();
        }

        private static boolean isPrimitive(Type type) {
            int sort = type.getSort();
            return sort != Type.VOID && sort != Type.ARRAY && sort != Type.OBJECT && sort != Type.METHOD;
        }

        private static void boxWrap(Type primitiveType, MethodVisitor mv) {
            Type objectType = toObject(primitiveType);
            mv.visitMethodInsn(INVOKESTATIC, objectType .getInternalName(), "valueOf",
                    "(" + primitiveType.getDescriptor() + ")" + objectType.getDescriptor());
        }

        private static void boxUnwrap(Type primitiveType, MethodVisitor mv) {
            Type objectType = toObject(primitiveType);
            mv.visitTypeInsn(CHECKCAST, objectType.getInternalName());
            mv.visitMethodInsn(INVOKEVIRTUAL, objectType.getInternalName(), primitiveType.getClassName() + "Value", "()" + primitiveType.getDescriptor());
        }
    }

}
