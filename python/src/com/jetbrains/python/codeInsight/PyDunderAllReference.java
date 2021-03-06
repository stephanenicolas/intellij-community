/*
 * Copyright 2000-2017 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.jetbrains.python.codeInsight;

import com.intellij.codeInsight.lookup.LookupElement;
import com.intellij.codeInsight.lookup.LookupElementBuilder;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiNamedElement;
import com.intellij.psi.PsiReferenceBase;
import com.intellij.psi.ResolveState;
import com.intellij.psi.scope.BaseScopeProcessor;
import com.intellij.util.ArrayUtil;
import com.intellij.util.containers.ContainerUtil;
import com.jetbrains.python.PyNames;
import com.jetbrains.python.psi.*;
import com.jetbrains.python.psi.impl.LightNamedElement;
import com.jetbrains.python.psi.types.PyModuleType;
import com.jetbrains.python.psi.resolve.RatedResolveResult;
import one.util.streamex.StreamEx;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * @author yole
 */
public class PyDunderAllReference extends PsiReferenceBase<PyStringLiteralExpression> {
  public PyDunderAllReference(@NotNull PyStringLiteralExpression element) {
    super(element);
    final List<TextRange> ranges = element.getStringValueTextRanges();
    if (!ranges.isEmpty()) {
      setRangeInElement(ranges.get(0));
    }
  }

  @Override
  public PsiElement resolve() {
    final PyStringLiteralExpression element = getElement();
    final String name = element.getStringValue();
    final PyFile file = (PyFile)element.getContainingFile();

    final List<RatedResolveResult> resolveResults = PyUtil.filterTopPriorityResults(file.multiResolveName(name));

    final boolean onlyDunderAlls = StreamEx
      .of(resolveResults)
      .map(RatedResolveResult::getElement)
      .allMatch(resolvedElement -> resolvedElement instanceof PyTargetExpression &&
                                   PyNames.ALL.equals(((PyTargetExpression)resolvedElement).getName()));

    if (onlyDunderAlls) return null;

    final RatedResolveResult resolveResult = ContainerUtil.getFirstItem(resolveResults);
    if (resolveResult == null) return null;
    return resolveResult.getElement();
  }

  @NotNull
  @Override
  public Object[] getVariants() {
    final List<LookupElement> result = new ArrayList<>();

    final PyFile containingFile = (PyFile)getElement().getContainingFile().getOriginalFile();

    final List<String> dunderAll = containingFile.getDunderAll();
    final Set<String> seenNames = new HashSet<>();
    if (dunderAll != null) {
      seenNames.addAll(dunderAll);
    }

    containingFile.processDeclarations(new BaseScopeProcessor() {
      @Override
      public boolean execute(@NotNull PsiElement element, @NotNull ResolveState state) {
        if (element instanceof PsiNamedElement && !(element instanceof LightNamedElement)) {
          final String name = ((PsiNamedElement)element).getName();
          if (name != null && PyUtil.getInitialUnderscores(name) == 0 && !seenNames.contains(name)) {
            seenNames.add(name);
            result.add(toLookupElement(name, element, true));
          }
        }
        else if (element instanceof PyImportElement) {
          final String visibleName = ((PyImportElement)element).getVisibleName();
          if (visibleName != null && !seenNames.contains(visibleName)) {
            seenNames.add(visibleName);
            result.add(toLookupElement(visibleName, element, false));
          }
        }
        return true;
      }
    }, ResolveState.initial(), null, containingFile);

    result.addAll(PyModuleType.getSubModuleVariants(containingFile.getParent(), getElement(), seenNames));

    return ArrayUtil.toObjectArray(result);
  }

  @NotNull
  private static LookupElement toLookupElement(@NotNull String name, @NotNull PsiElement element, boolean withIcon) {
    final LookupElementBuilder builder = LookupElementBuilder.createWithSmartPointer(name, element);
    return withIcon ? builder.withIcon(element.getIcon(0)) : builder;
  }
}
