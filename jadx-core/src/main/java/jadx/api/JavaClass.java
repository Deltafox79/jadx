package jadx.api;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.jetbrains.annotations.Nullable;

import jadx.core.dex.attributes.AFlag;
import jadx.core.dex.attributes.nodes.LineAttrNode;
import jadx.core.dex.info.AccessInfo;
import jadx.core.dex.nodes.ClassNode;
import jadx.core.dex.nodes.FieldNode;
import jadx.core.dex.nodes.MethodNode;

public final class JavaClass implements JavaNode {

	private final JadxDecompiler decompiler;
	private final ClassNode cls;
	private final JavaClass parent;

	private List<JavaClass> innerClasses = Collections.emptyList();
	private List<JavaField> fields = Collections.emptyList();
	private List<JavaMethod> methods = Collections.emptyList();
	private boolean listsLoaded;

	JavaClass(ClassNode classNode, JadxDecompiler decompiler) {
		this.decompiler = decompiler;
		this.cls = classNode;
		this.parent = null;
	}

	/**
	 * Inner classes constructor
	 */
	JavaClass(ClassNode classNode, JavaClass parent) {
		this.decompiler = null;
		this.cls = classNode;
		this.parent = parent;
	}

	public String getCode() {
		ICodeInfo code = cls.getCode();
		if (code == null) {
			decompile();
			code = cls.getCode();
			if (code == null) {
				return "";
			}
		}
		return code.getCodeStr();
	}

	public synchronized void decompile() {
		if (decompiler == null) {
			return;
		}
		if (cls.getCode() == null) {
			cls.decompile();
		}
	}

	public synchronized String getSmali() {
		if (decompiler == null) {
			return null;
		}
		if (cls.getSmali() == null) {
			decompiler.generateSmali(cls);
		}
		return cls.getSmali();
	}

	public synchronized void unload() {
		cls.unload();
		listsLoaded = false;
	}

	public ClassNode getClassNode() {
		return cls;
	}

	private void loadLists() {
		if (listsLoaded) {
			return;
		}
		listsLoaded = true;
		decompile();

		JadxDecompiler rootDecompiler = getRootDecompiler();
		int inClsCount = cls.getInnerClasses().size();
		if (inClsCount != 0) {
			List<JavaClass> list = new ArrayList<>(inClsCount);
			for (ClassNode inner : cls.getInnerClasses()) {
				if (!inner.contains(AFlag.DONT_GENERATE)) {
					JavaClass javaClass = new JavaClass(inner, this);
					javaClass.loadLists();
					list.add(javaClass);
					rootDecompiler.getClassesMap().put(inner, javaClass);
				}
			}
			this.innerClasses = Collections.unmodifiableList(list);
		}

		int fieldsCount = cls.getFields().size();
		if (fieldsCount != 0) {
			List<JavaField> flds = new ArrayList<>(fieldsCount);
			for (FieldNode f : cls.getFields()) {
				if (!f.contains(AFlag.DONT_GENERATE)) {
					JavaField javaField = new JavaField(f, this);
					flds.add(javaField);
					rootDecompiler.getFieldsMap().put(f, javaField);
				}
			}
			this.fields = Collections.unmodifiableList(flds);
		}

		int methodsCount = cls.getMethods().size();
		if (methodsCount != 0) {
			List<JavaMethod> mths = new ArrayList<>(methodsCount);
			for (MethodNode m : cls.getMethods()) {
				if (!m.contains(AFlag.DONT_GENERATE)) {
					JavaMethod javaMethod = new JavaMethod(this, m);
					mths.add(javaMethod);
					rootDecompiler.getMethodsMap().put(m, javaMethod);
				}
			}
			mths.sort(Comparator.comparing(JavaMethod::getName));
			this.methods = Collections.unmodifiableList(mths);
		}
	}

	private JadxDecompiler getRootDecompiler() {
		if (parent != null) {
			return parent.getRootDecompiler();
		}
		return decompiler;
	}

	private Map<CodePosition, Object> getCodeAnnotations() {
		decompile();
		ICodeInfo code = cls.getCode();
		if (code == null) {
			return Collections.emptyMap();
		}
		return code.getAnnotations();
	}

	public Map<CodePosition, JavaNode> getUsageMap() {
		Map<CodePosition, Object> map = getCodeAnnotations();
		if (map.isEmpty() || decompiler == null) {
			return Collections.emptyMap();
		}
		Map<CodePosition, JavaNode> resultMap = new HashMap<>(map.size());
		for (Map.Entry<CodePosition, Object> entry : map.entrySet()) {
			CodePosition codePosition = entry.getKey();
			Object obj = entry.getValue();
			if (obj instanceof LineAttrNode) {
				JavaNode node = getRootDecompiler().convertNode(obj);
				if (node != null) {
					resultMap.put(codePosition, node);
				}
			}
		}
		return resultMap;
	}

	@Nullable
	public JavaNode getJavaNodeAtPosition(int line, int offset) {
		decompile();
		return getRootDecompiler().getJavaNodeAtPosition(cls.getCode(), line, offset);
	}

	@Nullable
	public CodePosition getDefinitionPosition() {
		return getRootDecompiler().getDefinitionPosition(this);
	}

	public Integer getSourceLine(int decompiledLine) {
		decompile();
		return cls.getCode().getLineMapping().get(decompiledLine);
	}

	@Override
	public String getName() {
		return cls.getShortName();
	}

	@Override
	public String getFullName() {
		return cls.getFullName();
	}

	public String getPackage() {
		return cls.getPackage();
	}

	@Override
	public JavaClass getDeclaringClass() {
		return parent;
	}

	@Override
	public JavaClass getTopParentClass() {
		return parent == null ? this : parent.getTopParentClass();
	}

	public AccessInfo getAccessInfo() {
		return cls.getAccessFlags();
	}

	public List<JavaClass> getInnerClasses() {
		loadLists();
		return innerClasses;
	}

	public List<JavaField> getFields() {
		loadLists();
		return fields;
	}

	public List<JavaMethod> getMethods() {
		loadLists();
		return methods;
	}

	@Override
	public int getDecompiledLine() {
		return cls.getDecompiledLine();
	}

	@Override
	public boolean equals(Object o) {
		return this == o || o instanceof JavaClass && cls.equals(((JavaClass) o).cls);
	}

	@Override
	public int hashCode() {
		return cls.hashCode();
	}

	@Override
	public String toString() {
		return getFullName();
	}
}
