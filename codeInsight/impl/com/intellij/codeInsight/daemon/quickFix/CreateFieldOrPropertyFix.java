/*
 * Copyright (c) 2000-2006 JetBrains s.r.o. All Rights Reserved.
 */
package com.intellij.codeInsight.daemon.quickFix;

import com.intellij.codeInsight.CodeInsightUtil;
import com.intellij.codeInsight.daemon.QuickFixBundle;
import com.intellij.codeInsight.daemon.impl.quickfix.EmptyExpression;
import com.intellij.codeInsight.generation.ClassMember;
import com.intellij.codeInsight.generation.GenerateFieldOrPropertyHandler;
import com.intellij.codeInsight.generation.GenerateMembersUtil;
import com.intellij.codeInsight.generation.GenerationInfo;
import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.codeInsight.template.*;
import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.openapi.application.Result;
import com.intellij.openapi.command.CommandProcessor;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.*;
import com.intellij.psi.util.PropertyMemberType;
import com.intellij.psi.util.PropertyUtil;
import com.intellij.util.IncorrectOperationException;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * @author peter
*/
public class CreateFieldOrPropertyFix implements IntentionAction, LocalQuickFix {
  private static final Logger LOG = Logger.getInstance("com.intellij.codeInsight.daemon.quickFix.CreateFieldOrPropertyFix");

  private final PsiClass myClass;
  private final String myName;
  private final PsiType myType;
  private final PropertyMemberType myMemberType;
  private final PsiAnnotation[] myAnnotations;

  public CreateFieldOrPropertyFix(final PsiClass aClass, final String name, final PsiType type, final PropertyMemberType memberType, final PsiAnnotation[] annotations) {
    myClass = aClass;
    myName = name;
    myType = type;
    myMemberType = memberType;
    myAnnotations = annotations;
  }

  @NotNull
  public String getText() {
    return QuickFixBundle.message(myMemberType == PropertyMemberType.FIELD ? "create.field.text":"create.property.text", myName);
  }

  @NotNull
  public String getName() {
    return getText();
  }

  @NotNull
  public String getFamilyName() {
    return getText();
  }

  public void applyFix(@NotNull final Project project, @NotNull ProblemDescriptor descriptor) {
    final PsiFile file = myClass.getContainingFile();
    final Editor editor = CodeInsightUtil.positionCursor(project, myClass.getContainingFile(), myClass.getLBrace());
    if (isAvailable(project, editor, file)) {
      new WriteCommandAction(project) {
        protected void run(Result result) throws Throwable {
          invoke(project, editor, file);
        }
      }.execute();
    }
  }

  public boolean isAvailable(@NotNull Project project, Editor editor, PsiFile file) {
    return editor != null;
  }

  public void invoke(@NotNull Project project, Editor editor, PsiFile file) {
    CommandProcessor.getInstance().markCurrentCommandAsComplex(project);
    try {
      List<? extends GenerationInfo> prototypes = new GenerateFieldOrPropertyHandler(myName, myType, myMemberType, myAnnotations).generateMemberPrototypes(myClass, ClassMember.EMPTY_ARRAY);
      prototypes = GenerateMembersUtil.insertMembersAtOffset(myClass.getContainingFile(), editor.getCaretModel().getOffset(), prototypes);
      if (prototypes.isEmpty()) return;
      final PsiElement scope = prototypes.get(0).getPsiMember().getContext();
      assert scope != null;
      final Expression expression = new EmptyExpression() {
        public com.intellij.codeInsight.template.Result calculateResult(final ExpressionContext context) {
          return new TextResult(myType.getCanonicalText());
        }
      };
      final TemplateBuilder builder = new TemplateBuilder(scope);
      boolean first = true;
      @NonNls final String TYPE_NAME_VAR = "TYPE_NAME_VAR";
      for (GenerationInfo prototype : prototypes) {
        final PsiTypeElement typeElement = PropertyUtil.getPropertyTypeElement(prototype.getPsiMember());
        if (first) {
          first = false;
          builder.replaceElement(typeElement, TYPE_NAME_VAR, expression, true);
        }
        else {
          builder.replaceElement(typeElement, TYPE_NAME_VAR, TYPE_NAME_VAR, false);
        }
      }
      PsiDocumentManager.getInstance(project).doPostponedOperationsAndUnblockDocument(editor.getDocument());
      editor.getCaretModel().moveToOffset(scope.getTextRange().getStartOffset());
      TemplateManager.getInstance(project).startTemplate(editor, builder.buildInlineTemplate());
    }
    catch (IncorrectOperationException e) {
      LOG.error(e);
    }
  }

  public boolean startInWriteAction() {
    return true;
  }

}