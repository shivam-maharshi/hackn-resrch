package com.javacoders.websocketizer.parser.rest;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

import javax.ws.rs.Path;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseException;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.PackageDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.MarkerAnnotationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.PrimitiveType;
import com.github.javaparser.ast.type.ReferenceType;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
import com.javacoders.websocketizer.Framework;
import com.javacoders.websocketizer.InputParam;
import com.javacoders.websocketizer.MethodType;
import com.javacoders.websocketizer.ParamType;
import com.javacoders.websocketizer.parser.DirExplorer;
import com.javacoders.websocketizer.parser.ServiceExtractor;
import com.javacoders.websocketizer.rest.RestRequestContext;
import com.javacoders.websocketizer.rest.RestRequestHandler;
import com.javacoders.websocketizer.rest.RestServiceBlueprint;

/**
 * This class is responsible for extracting the key service HTTP interface
 * definition information from a JAX-RS application.
 * 
 * @author dedocibula
 * @author shivam.maharshi
 */
public class RestServiceExtractor implements ServiceExtractor<RestServiceBlueprint> {
  private static final String SPRING = "org.springframework";
  private static final String SEP = System.getProperty("file.separator");

  @Override
  public Collection<RestServiceBlueprint> extractBlueprints(File projectDir) {
    final List<RestServiceBlueprint> result = new ArrayList<>();

    new DirExplorer((level, path, file) -> path.endsWith(".java"), (level, path, file) -> {
      try {
        new VoidVisitorAdapter<Object>() {
          private String packageName;
          private String serviceUrl;

          Collection<RestServiceBlueprint> blueprints = new ArrayList<>();

          @Override
          public void visit(ClassOrInterfaceDeclaration n, Object arg) {
            super.visit(n, arg);
            Optional<AnnotationExpr> pathAnnotation = isRESTService(n);
            if (pathAnnotation.isPresent()) {
              serviceUrl = extractFilePath(pathAnnotation.get());
              for (RestServiceBlueprint blueprint : blueprints) {
                blueprint.setEndpoint(serviceUrl + blueprint.getEndpoint());
                blueprint.setClassName(n.getName());
                blueprint.setPackageName(packageName);
                blueprint.setRequestContext(new RestRequestContext(packageName + "." + n.getName()));
                String pattern = Pattern.quote(SEP);
                String[] pathSeg = file.getPath().split(pattern);
                String autoGenPath = "";
                for (int i = 0; i < pathSeg.length - 1; i++) {
                  autoGenPath += pathSeg[i] + SEP;
                }
                blueprint.setAutogeneratedPath(autoGenPath);
                pattern = Pattern.quote(SEP + "src");
                if (file.getPath().contains(SEP + "src")) {
                  String mainPath = SEP + "src" + SEP + "main" + SEP + "java" + SEP;
                  String testPath = SEP + "src" + SEP + "test" + SEP + "java" + SEP;
                  if (file.getPath().contains(mainPath)) {
                    blueprint.setSourceDir(file.getPath().split(pattern)[0] + mainPath);
                  } else if (file.getPath().contains(testPath)) {
                    blueprint.setSourceDir(file.getPath().split(pattern)[0] + testPath);
                  }
                } else {
                  blueprint.setSourceDir(getDirPath(file.getPath()));
                }
                result.add(blueprint);
              }
            }
          }

          @Override
          public void visit(PackageDeclaration n, Object arg) {
            super.visit(n, arg);
            packageName = n.getPackageName();
          }

          @Override
          public void visit(CompilationUnit n, Object arg) {
            super.visit(n, arg);
            for (ImportDeclaration declaration : n.getImports()) {
              String imp = declaration.getName().toString();
              String[] parts = imp.split("\\.");
              for (RestServiceBlueprint blueprint : blueprints) {
                blueprint.setFramework(imp.contains(SPRING) ? Framework.SPRING : Framework.DEFAULT);
                for (InputParam param : blueprint.getInputs()) {
                  String name = param.getDataType();
                  if (parts[parts.length - 1].equals(name))
                    param.setDataType(imp);
                }
              }
            }
          }

          @Override
          public void visit(MethodDeclaration n, Object arg) {
            super.visit(n, arg);
            if (n.getType() instanceof ReferenceType) {
              String methodUrl = "";
              List<MethodType> methodTypes = new ArrayList<>();
              List<AnnotationExpr> anotations = n.getAnnotations();
              for (AnnotationExpr annotation : anotations) {
                if (annotation instanceof MarkerAnnotationExpr) {
                  populateMethodTypes((MarkerAnnotationExpr) annotation, methodTypes);
                } else if (annotation instanceof SingleMemberAnnotationExpr) {
                  if (annotation.getName().getName().equals("Path")) {
                    methodUrl = ((StringLiteralExpr) ((SingleMemberAnnotationExpr) annotation).getMemberValue())
                        .getValue();
                  }
                }
              }
              for (MethodType methodType : methodTypes) {
                RestServiceBlueprint blueprint = new RestServiceBlueprint(methodUrl + "/" + methodType.name(), "",
                    fetchMethodInputs(n.getParameters()), new RestRequestHandler(n.getName(), methodType), null);
                blueprints.add(blueprint);
              }
            }
          }
        }.visit(JavaParser.parse(file), null);
      } catch (ParseException | IOException e) {
        // Do Nothing
      }
    }).explore(projectDir);
    return result;
  }

  private void populateMethodTypes(MarkerAnnotationExpr annotation, List<MethodType> httpMethods) {
    if (MethodType.getEnum(annotation.getName().getName()) != null) {
      httpMethods.add(MethodType.getEnum(annotation.getName().getName()));
    }
  }

  private List<InputParam> fetchMethodInputs(List<Parameter> params) {
    List<InputParam> l = new ArrayList<>();
    for (Parameter param : params) {
      if (param.getAnnotations().size() > 0) {
        AnnotationExpr an = param.getAnnotations().get(0);
        if (an instanceof SingleMemberAnnotationExpr
            && ((SingleMemberAnnotationExpr) an).getMemberValue() instanceof StringLiteralExpr) {
          if (param.getType() instanceof PrimitiveType) {
            l.add(new InputParam(param.getId().getName(),
                ((StringLiteralExpr) ((SingleMemberAnnotationExpr) an).getMemberValue()).getValue(),
                ((PrimitiveType) param.getType()).getType().name(), ParamType.getEnum(an.getName().getName())));
          } else if (param.getType() instanceof ReferenceType) {
            l.add(new InputParam(param.getId().getName(),
                ((StringLiteralExpr) ((SingleMemberAnnotationExpr) an).getMemberValue()).getValue(),
                ((ClassOrInterfaceType) ((ReferenceType) param.getType()).getType()).getName(),
                ParamType.getEnum(an.getName().getName())));
          }
        }
      } else {
        if (param.getType() instanceof PrimitiveType) {
          l.add(new InputParam(param.getId().getName(), param.getId().getName(),
              ((PrimitiveType) param.getType()).getType().name(), ParamType.BODY));
        } else if (param.getType() instanceof ReferenceType) {
          l.add(new InputParam(param.getId().getName(), param.getId().getName(),
              ((ClassOrInterfaceType) ((ReferenceType) param.getType()).getType()).getName(), ParamType.BODY));
        }
      }
    }
    return l;
  }

  private Optional<AnnotationExpr> isRESTService(ClassOrInterfaceDeclaration declaration) {
    for (AnnotationExpr expr : declaration.getAnnotations()) {
      if (Path.class.getSimpleName().equals(expr.getName().toString()))
        return Optional.of(expr);
    }
    return Optional.empty();
  }

  private String extractFilePath(AnnotationExpr annotation) {
    for (Node node : annotation.getChildrenNodes()) {
      if (node instanceof StringLiteralExpr)
        return ((StringLiteralExpr) node).getValue();
    }
    return "/";
  }

  private static String getDirPath(String filepath) {
    String pattern = Pattern.quote(SEP);
    String[] pathSegs = filepath.split(pattern);
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < pathSegs.length - 1; i++)
      sb.append(pathSegs[i] + SEP);
    return sb.toString();
  }
}
