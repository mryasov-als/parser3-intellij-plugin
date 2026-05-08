package ru.artlebedev.parser3.index;

import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ru.artlebedev.parser3.model.P3MethodDeclaration;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Совместимый фасад для резолва типов методов.
 * Сначала использует inferred result из тела метода, затем doc fallback.
 */
@Service(Service.Level.PROJECT)
public final class P3MethodDocTypeResolver {

	private final Project project;
	private static final boolean DEBUG_PERF = false;
	private static final int MAX_NESTED_METHOD_RESULT_DEPTH = 5;

	public P3MethodDocTypeResolver(@NotNull Project project) {
		this.project = project;
	}

	public static P3MethodDocTypeResolver getInstance(@NotNull Project project) {
		return project.getService(P3MethodDocTypeResolver.class);
	}

	public @Nullable P3ResolvedValue getResolvedResultByIndexQuery(
			@NotNull String methodName,
			@Nullable String ownerClass,
			@NotNull java.util.Set<VirtualFile> visibleFiles
	) {
		return getResolvedResultByIndexQueryInternal(methodName, ownerClass, visibleFiles, new HashSet<>());
	}

	private @Nullable P3ResolvedValue getResolvedResultByIndexQueryInternal(
			@NotNull String methodName,
			@Nullable String ownerClass,
			@NotNull java.util.Set<VirtualFile> visibleFiles,
			@NotNull Set<String> visited
	) {
		long t0 = DEBUG_PERF ? System.currentTimeMillis() : 0;
		com.intellij.util.indexing.FileBasedIndex index = com.intellij.util.indexing.FileBasedIndex.getInstance();
		com.intellij.psi.search.GlobalSearchScope scope =
				P3IndexMaintenance.getParser3IndexScope(project);

		final P3ResolvedValue[] exact = {null};
		final P3ResolvedValue[] fallback = {null};

		index.processValues(P3MethodFileIndex.NAME, methodName, null, (file, infos) -> {
			if (!visibleFiles.contains(file)) return true;

			for (P3MethodFileIndex.MethodInfo info : infos) {
				P3ResolvedValue resolved = resolveMethodInfo(info);
				resolved = bindSelfHashEntryMethodTargets(resolved, info.ownerClass);
				resolved = attachHashEntryFiles(resolved, file);
				resolved = resolveNestedMethodResultByIndex(resolved, info.ownerClass, visibleFiles, visited);
				if (resolved == null) continue;

				if (ownerClass == null) {
					if (info.ownerClass == null) {
						exact[0] = resolved;
						return false;
					}
				} else if (ownerClass.equals(info.ownerClass)) {
					exact[0] = resolved;
					return false;
				}

				fallback[0] = resolved;
			}
			return true;
		}, scope);

		P3ResolvedValue result = exact[0] != null ? exact[0] : fallback[0];

		if (DEBUG_PERF) {
			long elapsed = System.currentTimeMillis() - t0;
			if (elapsed > 2) {
				System.out.println("[DocTypeResolver.PERF] getResolvedResultByIndexQuery('" + methodName + "'): "
						+ elapsed + "ms, result=" + (result != null ? result.className : "null"));
			}
		}

		return result;
	}

	public @Nullable String getResultTypeByIndexQuery(
			@NotNull String methodName,
			@Nullable String ownerClass,
			@NotNull java.util.Set<VirtualFile> visibleFiles
	) {
		P3ResolvedValue value = getResolvedResultByIndexQuery(methodName, ownerClass, visibleFiles);
		return value != null ? value.className : null;
	}

	public @Nullable P3ResolvedValue getMethodResolvedResult(
			@NotNull String methodName,
			@Nullable String ownerClass,
			@NotNull List<VirtualFile> visibleFiles
	) {
		return getMethodResolvedResultInternal(methodName, ownerClass, visibleFiles, new HashSet<>());
	}

	private @Nullable P3ResolvedValue getMethodResolvedResultInternal(
			@NotNull String methodName,
			@Nullable String ownerClass,
			@NotNull List<VirtualFile> visibleFiles,
			@NotNull Set<String> visited
	) {
		long t0 = DEBUG_PERF ? System.currentTimeMillis() : 0;
		P3MethodIndex methodIndex = P3MethodIndex.getInstance(project);

		List<P3MethodDeclaration> methods = methodIndex.findInFiles(methodName, visibleFiles);
		if (methods.isEmpty()) {
			return null;
		}

		P3MethodDeclaration targetMethod = null;
		for (P3MethodDeclaration method : methods) {
			String methodOwner = method.getOwnerClass();
			if (ownerClass == null) {
				if (methodOwner == null) {
					targetMethod = method;
					break;
				}
			} else if (ownerClass.equals(methodOwner)) {
				targetMethod = method;
				break;
			}
		}

		if (targetMethod == null && !methods.isEmpty()) {
			targetMethod = methods.get(methods.size() - 1);
		}
		if (targetMethod == null) {
			return null;
		}

		P3ResolvedValue result = targetMethod.getInferredResult();
		if (result == null) {
			String docType = targetMethod.getDocResultType();
			if (docType != null) {
				result = P3ResolvedValue.of(docType, null, null, null, null, null, null);
			}
		}
		result = bindSelfHashEntryMethodTargets(result, targetMethod.getOwnerClass());
		result = attachHashEntryFiles(result, targetMethod.getFile());
		result = resolveNestedMethodResult(result, targetMethod.getOwnerClass(), visibleFiles, visited);
		result = resolveNestedHashEntryMethodResults(
				result,
				targetMethod.getOwnerClass(),
				visibleFiles,
				visited,
				MAX_NESTED_METHOD_RESULT_DEPTH
		);

		if (DEBUG_PERF) {
			long elapsed = System.currentTimeMillis() - t0;
			if (elapsed > 5) {
				System.out.println("[DocTypeResolver.PERF] getMethodResolvedResult('" + methodName + "'): "
						+ elapsed + "ms");
			}
		}

		return result;
	}

	public @Nullable String getMethodResultType(
			@NotNull String methodName,
			@Nullable String ownerClass,
			@NotNull List<VirtualFile> visibleFiles
	) {
		P3ResolvedValue value = getMethodResolvedResult(methodName, ownerClass, visibleFiles);
		return value != null ? value.className : null;
	}

	public @Nullable String getMethodParamType(
			@NotNull String methodName,
			@NotNull String paramName,
			@Nullable String ownerClass,
			@NotNull List<VirtualFile> visibleFiles
	) {
		P3MethodIndex methodIndex = P3MethodIndex.getInstance(project);
		List<P3MethodDeclaration> methods = methodIndex.findInFiles(methodName, visibleFiles);
		if (methods.isEmpty()) {
			return null;
		}

		P3MethodDeclaration targetMethod = null;
		for (P3MethodDeclaration method : methods) {
			String methodOwner = method.getOwnerClass();
			if (ownerClass == null) {
				if (methodOwner == null) {
					targetMethod = method;
					break;
				}
			} else if (ownerClass.equals(methodOwner)) {
				targetMethod = method;
				break;
			}
		}

		if (targetMethod == null && !methods.isEmpty()) {
			targetMethod = methods.get(methods.size() - 1);
		}

		return targetMethod != null ? targetMethod.getParamType(paramName) : null;
	}

	private static @Nullable P3ResolvedValue resolveMethodInfo(@NotNull P3MethodFileIndex.MethodInfo info) {
		if (info.inferredResult != null) {
			return info.inferredResult;
		}
		if (info.docResult != null && info.docResult.type != null) {
			return P3ResolvedValue.of(info.docResult.type, null, null, null, null, null, null);
		}
		return null;
	}

	private static @Nullable P3ResolvedValue attachHashEntryFiles(
			@Nullable P3ResolvedValue value,
			@NotNull VirtualFile file
	) {
		if (value == null || value.hashKeys == null || value.hashKeys.isEmpty()) return value;

		java.util.Map<String, HashEntryInfo> hashKeys = new java.util.LinkedHashMap<>(value.hashKeys.size());
		for (java.util.Map.Entry<String, HashEntryInfo> entry : value.hashKeys.entrySet()) {
			hashKeys.put(entry.getKey(), entry.getValue().withFile(file));
		}
		return P3ResolvedValue.of(
				value.className,
				value.columns,
				value.sourceVarKey,
				hashKeys,
				value.hashSourceVars,
				value.methodName,
				value.targetClassName,
				value.receiverVarKey
		);
	}

	private @Nullable P3ResolvedValue resolveNestedMethodResult(
			@Nullable P3ResolvedValue value,
			@Nullable String ownerClass,
			@NotNull List<VirtualFile> visibleFiles,
			@NotNull Set<String> visited
	) {
		P3ResolvedValue current = value;
		int depth = 0;
		while (current != null
				&& P3VariableFileIndex.VariableTypeInfo.METHOD_CALL_MARKER.equals(current.className)
				&& current.methodName != null
				&& depth < MAX_NESTED_METHOD_RESULT_DEPTH) {
			depth++;
			String targetOwnerClass = current.targetClassName;
			if ((targetOwnerClass == null || targetOwnerClass.isEmpty())
					&& "self".equals(current.receiverVarKey)
					&& ownerClass != null && !ownerClass.isEmpty()) {
				targetOwnerClass = ownerClass;
			}
			if (current.receiverVarKey != null
					&& !"self".equals(current.receiverVarKey)
					&& (targetOwnerClass == null || targetOwnerClass.isEmpty())) {
				return current;
			}
			String visitKey = current.methodName + "|" + targetOwnerClass + "|" + current.receiverVarKey;
			if (!visited.add(visitKey)) {
				return current;
			}

			P3ResolvedValue next = getMethodResolvedResultInternal(current.methodName, targetOwnerClass, visibleFiles, visited);
			if (next == null || next.equals(current)) {
				return current;
			}
			current = next;
			ownerClass = targetOwnerClass;
		}
		return current;
	}

	private @Nullable P3ResolvedValue resolveNestedMethodResultByIndex(
			@Nullable P3ResolvedValue value,
			@Nullable String ownerClass,
			@NotNull Set<VirtualFile> visibleFiles,
			@NotNull Set<String> visited
	) {
		P3ResolvedValue current = value;
		int depth = 0;
		while (current != null
				&& P3VariableFileIndex.VariableTypeInfo.METHOD_CALL_MARKER.equals(current.className)
				&& current.methodName != null
				&& depth < MAX_NESTED_METHOD_RESULT_DEPTH) {
			depth++;
			String targetOwnerClass = current.targetClassName;
			if ((targetOwnerClass == null || targetOwnerClass.isEmpty())
					&& "self".equals(current.receiverVarKey)
					&& ownerClass != null && !ownerClass.isEmpty()) {
				targetOwnerClass = ownerClass;
			}
			if (current.receiverVarKey != null
					&& !"self".equals(current.receiverVarKey)
					&& (targetOwnerClass == null || targetOwnerClass.isEmpty())) {
				return current;
			}
			String visitKey = current.methodName + "|" + targetOwnerClass + "|" + current.receiverVarKey;
			if (!visited.add(visitKey)) {
				return current;
			}

			P3ResolvedValue next = getResolvedResultByIndexQueryInternal(current.methodName, targetOwnerClass, visibleFiles, visited);
			if (next == null || next.equals(current)) {
				return current;
			}
			current = next;
			ownerClass = targetOwnerClass;
		}
		return current;
	}

	private @Nullable P3ResolvedValue resolveNestedHashEntryMethodResults(
			@Nullable P3ResolvedValue value,
			@Nullable String ownerClass,
			@NotNull List<VirtualFile> visibleFiles,
			@NotNull Set<String> visited,
			int maxDepth
	) {
		if (value == null || value.hashKeys == null || value.hashKeys.isEmpty() || maxDepth <= 0) {
			return value;
		}
		java.util.Map<String, HashEntryInfo> hashKeys =
				resolveNestedHashEntryMethodResults(value.hashKeys, ownerClass, visibleFiles, visited, maxDepth);
		if (hashKeys == value.hashKeys) return value;
		return P3ResolvedValue.of(
				value.className,
				value.columns,
				value.sourceVarKey,
				hashKeys,
				value.hashSourceVars,
				value.methodName,
				value.targetClassName,
				value.receiverVarKey
		);
	}

	private @NotNull java.util.Map<String, HashEntryInfo> resolveNestedHashEntryMethodResults(
			@NotNull java.util.Map<String, HashEntryInfo> hashKeys,
			@Nullable String ownerClass,
			@NotNull List<VirtualFile> visibleFiles,
			@NotNull Set<String> visited,
			int maxDepth
	) {
		java.util.Map<String, HashEntryInfo> result = null;
		for (java.util.Map.Entry<String, HashEntryInfo> entry : hashKeys.entrySet()) {
			HashEntryInfo oldInfo = entry.getValue();
			HashEntryInfo newInfo = resolveNestedHashEntryMethodResult(
					oldInfo,
					ownerClass,
					visibleFiles,
					visited,
					maxDepth
			);
			if (newInfo != oldInfo) {
				if (result == null) result = new java.util.LinkedHashMap<>(hashKeys);
				result.put(entry.getKey(), newInfo);
			}
		}
		return result != null ? result : hashKeys;
	}

	private @NotNull HashEntryInfo resolveNestedHashEntryMethodResult(
			@NotNull HashEntryInfo info,
			@Nullable String ownerClass,
			@NotNull List<VirtualFile> visibleFiles,
			@NotNull Set<String> visited,
			int maxDepth
	) {
		if (maxDepth <= 0) return info;

		if (P3VariableFileIndex.VariableTypeInfo.METHOD_CALL_MARKER.equals(info.className)
				&& info.methodName != null
				&& !info.methodName.isEmpty()) {
			String targetOwnerClass = info.targetClassName;
			if ((targetOwnerClass == null || targetOwnerClass.isEmpty())
					&& "self".equals(info.receiverVarKey)
					&& ownerClass != null && !ownerClass.isEmpty()) {
				targetOwnerClass = ownerClass;
			}
			if (info.receiverVarKey != null
					&& !"self".equals(info.receiverVarKey)
					&& (targetOwnerClass == null || targetOwnerClass.isEmpty())) {
				return info;
			}

			String visitKey = "hash-entry|" + info.methodName + "|" + targetOwnerClass + "|" + info.receiverVarKey;
			if (!visited.add(visitKey)) return info;

			P3ResolvedValue resolved = getMethodResolvedResultInternal(
					info.methodName,
					targetOwnerClass,
					preferEntryFile(visibleFiles, info.file),
					visited
			);
			resolved = bindSelfHashEntryMethodTargets(resolved, targetOwnerClass);
			resolved = resolveNestedHashEntryMethodResults(
					resolved,
					targetOwnerClass,
					visibleFiles,
					visited,
					maxDepth - 1
			);
			if (resolved == null || resolved.isUnknown()) return info;

			return new HashEntryInfo(
					resolved.className,
					resolved.columns != null ? resolved.columns : info.columns,
					P3VariableEffectiveShape.mergeHashEntryMaps(resolved.hashKeys, info.nestedKeys),
					info.offset,
					info.file,
					resolved.sourceVarKey != null ? resolved.sourceVarKey : info.sourceVarKey,
					resolved.methodName != null ? resolved.methodName : info.methodName,
					resolved.targetClassName != null ? resolved.targetClassName : info.targetClassName,
					resolved.receiverVarKey != null ? resolved.receiverVarKey : info.receiverVarKey
			);
		}

		if (info.nestedKeys == null || info.nestedKeys.isEmpty()) return info;
		java.util.Map<String, HashEntryInfo> nestedKeys =
				resolveNestedHashEntryMethodResults(info.nestedKeys, ownerClass, visibleFiles, visited, maxDepth);
		if (nestedKeys == info.nestedKeys) return info;
		return new HashEntryInfo(
				info.className,
				info.columns,
				nestedKeys,
				info.offset,
				info.file,
				info.sourceVarKey,
				info.methodName,
				info.targetClassName,
				info.receiverVarKey
		);
	}

	private static @Nullable P3ResolvedValue bindSelfHashEntryMethodTargets(
			@Nullable P3ResolvedValue value,
			@Nullable String ownerClass
	) {
		if (value == null || ownerClass == null || ownerClass.isEmpty()
				|| value.hashKeys == null || value.hashKeys.isEmpty()) {
			return value;
		}
		java.util.Map<String, HashEntryInfo> hashKeys = bindSelfHashEntryMethodTargets(value.hashKeys, ownerClass);
		if (hashKeys == value.hashKeys) return value;
		return P3ResolvedValue.of(
				value.className,
				value.columns,
				value.sourceVarKey,
				hashKeys,
				value.hashSourceVars,
				value.methodName,
				value.targetClassName,
				value.receiverVarKey
		);
	}

	private static @NotNull java.util.Map<String, HashEntryInfo> bindSelfHashEntryMethodTargets(
			@NotNull java.util.Map<String, HashEntryInfo> hashKeys,
			@NotNull String ownerClass
	) {
		java.util.Map<String, HashEntryInfo> result = null;
		for (java.util.Map.Entry<String, HashEntryInfo> entry : hashKeys.entrySet()) {
			HashEntryInfo oldInfo = entry.getValue();
			HashEntryInfo newInfo = bindSelfHashEntryMethodTarget(oldInfo, ownerClass);
			if (newInfo != oldInfo) {
				if (result == null) result = new java.util.LinkedHashMap<>(hashKeys);
				result.put(entry.getKey(), newInfo);
			}
		}
		return result != null ? result : hashKeys;
	}

	private static @NotNull HashEntryInfo bindSelfHashEntryMethodTarget(
			@NotNull HashEntryInfo info,
			@NotNull String ownerClass
	) {
		java.util.Map<String, HashEntryInfo> nestedKeys = info.nestedKeys;
		if (nestedKeys != null && !nestedKeys.isEmpty()) {
			nestedKeys = bindSelfHashEntryMethodTargets(nestedKeys, ownerClass);
		}
		boolean bindSelfTarget = "self".equals(info.receiverVarKey)
				&& (info.targetClassName == null || info.targetClassName.isEmpty());
		if (!bindSelfTarget && nestedKeys == info.nestedKeys) {
			return info;
		}
		return new HashEntryInfo(
				info.className,
				info.columns,
				nestedKeys,
				info.offset,
				info.file,
				info.sourceVarKey,
				info.methodName,
				bindSelfTarget ? ownerClass : info.targetClassName,
				info.receiverVarKey
		);
	}

	private static @NotNull List<VirtualFile> preferEntryFile(
			@NotNull List<VirtualFile> visibleFiles,
			@Nullable VirtualFile entryFile
	) {
		if (entryFile == null || !visibleFiles.contains(entryFile)) return visibleFiles;
		java.util.ArrayList<VirtualFile> result = new java.util.ArrayList<>(visibleFiles.size());
		result.add(entryFile);
		for (VirtualFile file : visibleFiles) {
			if (!file.equals(entryFile)) result.add(file);
		}
		return result;
	}
}
