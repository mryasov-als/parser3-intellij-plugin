package ru.artlebedev.parser3;

import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import junit.framework.TestCase;
import ru.artlebedev.parser3.utils.Parser3PsiUtils;

import java.lang.reflect.Proxy;

public class x13_InjectedHtmlSafetyTest extends TestCase {

	public void testInjectedFragmentIsDetectedByHostContext() {
		PsiFile hostFile = psiFile(null);
		PsiElement context = psiElement(hostFile);
		PsiFile injectedFile = psiFile(context);

		assertFalse("Regular file must not be treated as injected", Parser3PsiUtils.isInjectedFragment(hostFile));
		assertTrue("Injected file must be detected via context host", Parser3PsiUtils.isInjectedFragment(injectedFile));
	}

	private static PsiFile psiFile(PsiElement context) {
		return (PsiFile) Proxy.newProxyInstance(
				x13_InjectedHtmlSafetyTest.class.getClassLoader(),
				new Class<?>[]{PsiFile.class},
				(proxy, method, args) -> {
					String name = method.getName();
					if ("getContext".equals(name)) {
						return context;
					}
					if ("getContainingFile".equals(name)) {
						return proxy;
					}
					if ("toString".equals(name)) {
						return context == null ? "hostFile" : "injectedFile";
					}
					if ("hashCode".equals(name)) {
						return System.identityHashCode(proxy);
					}
					if ("equals".equals(name)) {
						return proxy == args[0];
					}
					return null;
				}
		);
	}

	private static PsiElement psiElement(PsiFile hostFile) {
		return (PsiElement) Proxy.newProxyInstance(
				x13_InjectedHtmlSafetyTest.class.getClassLoader(),
				new Class<?>[]{PsiElement.class},
				(proxy, method, args) -> {
					String name = method.getName();
					if ("getContainingFile".equals(name)) {
						return hostFile;
					}
					if ("toString".equals(name)) {
						return "contextElement";
					}
					if ("hashCode".equals(name)) {
						return System.identityHashCode(proxy);
					}
					if ("equals".equals(name)) {
						return proxy == args[0];
					}
					return null;
				}
		);
	}
}
