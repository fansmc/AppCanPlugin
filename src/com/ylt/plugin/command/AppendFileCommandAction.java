package com.ylt.plugin.command;

import com.intellij.openapi.application.Result;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.project.Project;
import com.intellij.psi.*;
import com.intellij.psi.codeStyle.JavaCodeStyleManager;
import com.intellij.psi.search.EverythingGlobalScope;
import com.intellij.psi.search.FilenameIndex;
import com.intellij.psi.search.GlobalSearchScope;
import com.ylt.plugin.util.StringUtil;
import com.ylt.plugin.util.Util;
import com.ylt.plugin.vo.XmlItem;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Created by ylt on 15/3/24.
 */
public class AppendFileCommandAction extends WriteCommandAction<PsiFile> {

    /** Current project. */
    private final Project project;

    /** Current file. */
    private final PsiFile file;

    /** Rules set to add. */
    private final List<XmlItem> content;

    protected PsiElementFactory mFactory;

    private final Module module;

    private PsiClass psiClass;
    /**
     * Builds a new instance of {@link com.ylt.plugin.command.AppendFileCommandAction}.
     * Takes a {@link java.util.Set} of the rules to add.
     *
     * @param project current project
     * @param file    working file
     * @param content rules set
     */
    public AppendFileCommandAction(@NotNull Project project, @NotNull PsiFile file, @NotNull List<XmlItem> content,@NotNull Module module) {
        super(project, file);
        this.project = project;
        this.file = file;
        this.content = content;
        this.module=module;
        mFactory = JavaPsiFacade.getElementFactory(project);
    }

    @Override
    protected void run(Result<PsiFile> result) throws Throwable {
        if (content.isEmpty()) {
            return;
        }


        Document document = PsiDocumentManager.getInstance(project).getDocument(file);

        if (document != null) {
            for (PsiElement element : file.getChildren()) {
                if (content.contains(element.getText())) {

                    content.remove(element.getText());
                }
            }
            PsiDocumentManager.getInstance(project).commitDocument(document);
        }
        generateMainClass();
        JavaCodeStyleManager styleManager = JavaCodeStyleManager.getInstance(project);
        styleManager.optimizeImports(file);
        styleManager.shortenClassReferences(psiClass);
    }

    public void generateMainClass(){
//        VirtualFile mainClassFile=project.getProjectFile().findChild(Util.getEnterClassNameByProjectName(project.getName())+".java");
        String mainClassName=Util.getEnterClassNameByProjectName(module.getName());
        PsiFile[] files= FilenameIndex.getFilesByName(project,mainClassName+".java",new EverythingGlobalScope(project));
        if (files.length<=0){
            return;
        }
        PsiJavaFile mainClassFile=(PsiJavaFile)files[0];

        PsiFile[] jsFiles= FilenameIndex.getFilesByName(project,"JsConst.java",new EverythingGlobalScope(project));
        if (files.length<=0){
            return;
        }
        PsiJavaFile jsJavaFile=(PsiJavaFile)jsFiles[0];
        PsiClass jsClass=JavaPsiFacade.getInstance(project).findClass(mainClassFile.getPackageName() + "." + "JsConst", GlobalSearchScope.allScope(project));
        psiClass= JavaPsiFacade.getInstance(project).findClass(mainClassFile.getPackageName() + "." + mainClassName, GlobalSearchScope.allScope(project));
        int index=getIndex(psiClass);
        for (XmlItem method:content){

            if (method.getType()==2||method.getType()==1){
                //添加JS 回调
                if (!hasJsConst(jsClass,method)){
                    addJSConst(jsClass,method,module.getName());
                }
            }

            if (method.getType()==0||method.getType()==1){
                //添加方法
                if (hasMethod(psiClass, method.getMethodName())){
                    continue;
                }
                addMethodAndFiled(psiClass, method, index);
                index++;
            }

        }
    }

    private void addJSConst(PsiClass psiClass,XmlItem xmlItem,String moduleName){
        psiClass.add(mFactory.createFieldFromText(getJsConstByName(xmlItem,moduleName),psiClass));
    }

    public void addMethodAndFiled(PsiClass psiClass,XmlItem method,int index){
        psiClass.add(mFactory.createFieldFromText(createStaticFiled(method.getMethodName(),index),psiClass));
        psiClass.addBefore(mFactory.createMethodFromText(createMethod(method.getMethodName()),psiClass),psiClass.findMethodsByName("onHandleMessage",true)[0]);
        psiClass.addBefore(mFactory.createMethodFromText(createMsgMethod(method),psiClass),psiClass.findMethodsByName("onHandleMessage",true)[0]);
        addHandleMethod(psiClass,method.getMethodName());
    }

    protected String getJsConstByName(XmlItem method,String moduleName){
        String methodName=method.getMethodName();
        if (method.getType()==1) {
            StringBuilder stringBuilder = new StringBuilder();
            stringBuilder.append("public static final String ")
                    .append(getFieldName(method))
                    .append(" = \"")
                    .append(moduleName)
                    .append(".cb")
                    .append(methodName.substring(0, 1).toUpperCase())
                    .append(methodName.substring(1, methodName.length()))
                    .append("\";");
            return stringBuilder.toString();
        }else{
            //==2
            StringBuilder stringBuilder = new StringBuilder();
            stringBuilder.append("public static final String ")
                    .append(getFieldName(method))
                    .append(" = \"")
                    .append(moduleName)
                    .append(".")
                    .append(methodName)
                    .append("\";");
            return stringBuilder.toString();
        }

    }

    private String getFieldName(XmlItem method){
        if (method.getType()==1){
            return "CALLBACK_"+StringUtil.getStaticFieldName(method.getMethodName());
        }else {
            return StringUtil.getStaticFieldName(method.getMethodName());
        }
    }

    protected void addHandleMethod(PsiClass psiClass, String methodName){
        PsiMethod[] methods=psiClass.findMethodsByName("onHandleMessage", true);
        PsiMethod method=methods[0];
        PsiElement[] elements=method.getBody().getChildren();
        for (PsiElement element:elements){
            if (element instanceof PsiSwitchStatement){
                PsiSwitchStatement switchStatement= (PsiSwitchStatement) element;
                PsiElement[] psiElements=switchStatement.getChildren();
                for (PsiElement psiElement:psiElements){
                    if (psiElement instanceof  PsiCodeBlock){
                        PsiCodeBlock codeBlock= (PsiCodeBlock) psiElement;
                        codeBlock.getChildren();
                        PsiStatement statement=PsiElementFactory.SERVICE.getInstance(project).createStatementFromText(getCaseState(methodName), null);
                        PsiStatement defaultStatement1=getDefaultStatement(codeBlock.getStatements());
                        codeBlock.addBefore(statement,defaultStatement1);
                        codeBlock.addBefore(mFactory.createStatementFromText(getCaseBlock(methodName),null),defaultStatement1);
                        codeBlock.addBefore(mFactory.createStatementFromText("break;", null), defaultStatement1);
                        return;
                    }
                }
            }

        }
    }

    private PsiStatement getDefaultStatement(PsiStatement[] psiStatements){
        for (PsiStatement statement:psiStatements){
            if (statement.getText().equals("default:")){
                return statement;
            }
        }
        return null;
    }

    private String getCaseState(String method){
        StringBuilder builder=new StringBuilder();
        builder.append("case MSG_")
                .append(StringUtil.getStaticFieldName(method))
                .append(":");
        return builder.toString();
    }

    private String getCaseBlock(String method){
        StringBuilder builder=new StringBuilder();
        builder.append(method)
                .append("Msg(bundle.getStringArray(BUNDLE_DATA));");
        return builder.toString();
    }

    private boolean hasMethod(PsiClass psiClass,String methodName){
        PsiMethod[] methods=psiClass.findMethodsByName(methodName, true);
        if (methods==null||methods.length==0){
            return false;
        }
        return true;
    }

    private boolean hasJsConst(PsiClass psiClass,XmlItem method){
        PsiField field=psiClass.findFieldByName(getFieldName(method), true);
        if (field==null){
            return false;
        }
        return true;
    }

    private int getIndex(PsiClass psiClass){
        int index=0;
        PsiField[] psiFields=psiClass.getAllFields();
        for (int i=0;i<psiFields.length;i++){
            if (psiFields[i].getName().contains("MSG_")){
                for (PsiElement element:psiFields[i].getChildren()){
                    if (element instanceof PsiLiteralExpression){
                        PsiLiteralExpression expression= (PsiLiteralExpression) element;
                        index= Integer.parseInt(expression.getText());

                    }
                }
             }
        }
        return index+1;
    }

    protected String createStaticFiled(String methodName, int index){
        StringBuilder stringBuilder=new StringBuilder("private static final int MSG_")
                .append(StringUtil.getStaticFieldName(methodName))
                .append("=")
                .append(index)
                .append(";\n");
        return  stringBuilder.toString();
    }

    protected String createMethod(String methodName){
        StringBuilder stringBuilder=new StringBuilder("public void ");
        stringBuilder.append(methodName)
                .append("(String[] params){\n")
                .append("if (params == null || params.length < 1) {\n" +
                        "            errorCallback(0, 0, \"error params!\");\n" +
                        "            return;\n" +
                        "        }\n" +
                        "        Message msg = new Message();\n" +
                        "        msg.obj = this;\n" +
                        "        msg.what = MSG_")
                .append(StringUtil.getStaticFieldName(methodName))
                .append(";\n" +
                        "        Bundle bd = new Bundle();\n" +
                        "        bd.putStringArray(BUNDLE_DATA, params);\n" +
                        "        msg.setData(bd);\n" +
                        "        mHandler.sendMessage(msg);\n" +
                        "    }");
        return  stringBuilder.toString();
    }

    protected String createMsgMethod(XmlItem method){
        String methodName=method.getMethodName();
        StringBuilder stringBuilder=new StringBuilder("private void ");
        stringBuilder.append(methodName)
        .append("Msg(String[] params){\n" +
                "        String json=params[0];\n");
        if (method.getType()==1){
            //cb回调
            stringBuilder.append("JSONObject jsonResult=new JSONObject();\n" +
                    "        try {\n" +
                    "            jsonResult.put(\"\",\"\");")
                    .append("} catch (JSONException e) {\n" +
                            "        }\n")
                    .append("callBackPluginJs(JsConst.CALLBACK_")
                    .append(StringUtil.getStaticFieldName(methodName))
                    .append(", jsonResult.toString());\n");
        }
        stringBuilder.append("    }");
        return  stringBuilder.toString();
    }

}
