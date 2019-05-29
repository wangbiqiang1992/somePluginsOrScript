import com.intellij.database.model.DasTable
import com.intellij.database.model.ObjectKind
import com.intellij.database.util.Case
import com.intellij.database.util.DasUtil
import groovy.xml.MarkupBuilder

/*
 * Available context bindings:
 *   SELECTION   Iterable<DasObject>
 *   PROJECT     project
 *   FILES       files helper
 */
packageName = ""
typeMapping = [
    (~/(?i)tinyint|smallint|mediumint/)      : "Integer",
    (~/(?i)int|number/)                      : "Long",
    (~/(?i)bool|bit/)                        : "Boolean",
    (~/(?i)float|double|decimal|real/)       : "Double",
    (~/(?i)datetime|timestamp|date|time/)    : "Date",
    (~/(?i)blob|binary|bfile|clob|raw|image/): "InputStream",
    (~/(?i)/)                                : "String"
]

FILES.chooseDirectoryAndSave("Choose directory", "Choose where to store generated files") { dir ->
    SELECTION.filter { it instanceof DasTable && it.getKind() == ObjectKind.TABLE }.each { generate(it, dir) }
}

def generate(table, dir) {
    packageName = getPackageName(dir)
    def className = javaClassName(table.getName(), true)
    def fields = calcFields(table)
    packageName = getPackageName(dir)
    //编写xml文件
    PrintWriter xmlWriter = new PrintWriter(new OutputStreamWriter(new FileOutputStream(new File(dir, className + "Mapper.xml")), "UTF-8"))
    xmlWriter.withPrintWriter { out -> generateXmlFile(out, className, fields, table, packageName) }
    //编写PO文件
    PrintWriter pojoWriter = new PrintWriter(new OutputStreamWriter(new FileOutputStream(new File(dir, className + "PO.java")), "UTF-8"))
    pojoWriter.withPrintWriter { out -> generatePOFile(out, className, fields, packageName) }
    //编写Mapper.java文件
    PrintWriter mapperWriter = new PrintWriter(new OutputStreamWriter(new FileOutputStream(new File(dir, className + "Mapper.java")), "UTF-8"))
    mapperWriter.withPrintWriter { out -> generateMapperFile(out, className, fields, packageName) }
}

// 获取包所在文件夹路径
def getPackageName(dir) {
    return dir.toString().replaceAll("\\\\", ".").replaceAll("/", ".").replaceAll("^.*src(\\.main\\.java\\.)?", "") + ";"
}

def generateXmlFile(out, className, fields, table, packageName) {
    out.println '''<!DOCTYPE mapper'''
    out.println '''PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"'''
    out.println '''"http://mybatis.org/dtd/mybatis-3-mapper.dtd">'''

    def pojoName = Case.LOWER.apply(className[0]) + className[1..-1]
    def realPackage = packageName.substring(0, packageName.indexOf(";"))
    Map jdbcTypes = ["Long": "DECIMAL", "Integer": "DECIMAL", "String": "VARCHAR", "Date": "TIMESTAMP"]
    def primaryColumn = fields.find { it.annos.contains("@Id") }

    def strXml = new StringWriter()
    MarkupBuilder mb = new MarkupBuilder(strXml);
    mb.useDoubleQuotes = true
    mb.mapper(namespace: "${realPackage}.${className}Mapper") {
        resultMap(id: "${pojoName}Map", type: "${realPackage}.${className}PO") {
            id(column: "${primaryColumn.colName}", property: "${primaryColumn.name}")
            fields.each {
                if (it != primaryColumn) {
                    result(column: "${it.colName}", property: "${it.name}")
                }
            }
        }
        select(id: "select${className}ById", resultMap: "${pojoName}Map", "select * from ${table.getName()} where ${primaryColumn.colName}=#{id}")
        select(id: "select${className}s", resultMap: "${pojoName}Map", parameterType: "java.util.Map", "select * from ${table.getName()} where 1=1 ") {
            fields.each {
                if (it.type == "Date") {
                    fistate(test: "${it.name} != null", " and ${it.colName}=#{${it.name},jdbcType=TIMESTAMP}")
                } else {
                    fistate(test: "${it.name} != null", " and ${it.colName}=\'\${${it.name}}'")
                }
            }
        }
        insert(id: "insert${className}", "parameterType": "${realPackage}.${className}PO") {
            selectKey(resultType: "java.lang.Long", order: "BEFORE", keyProperty: "${pojoName}.id",
                "select " + table.getName() + "_seq.nextval from dual")
            insertsql("")
            trim(prefix: "(", suffix: ")", suffixOverrides: ",") {
                fields.each {
                    fistate(test: "${it.name} != null", "${it.name},")
                }
            }
            trim("prefix": "values (", suffix: ")", suffixOverrides: ",") {
                fields.each {
                    def jdbcType = jdbcTypes.get(it.type)
                    fistate(test: "${it.name} != null", "#{${pojoName}.${it.name},jdbcType=${jdbcType}},")
                }
            }
        }
        update(id: "update${className}", "parameterType": "${realPackage}.${className}PO") {
            updatesql("")
            trim(prefix: "set", suffixOverrides: ",") {
                fields.each {
                    def jdbcType = jdbcTypes.get(it.type)
                    fistate(test: "${it.name} != null", "${it.colName}=#{${pojoName}.${it.name},jdbcType=${jdbcType}},")
                }
            }
            updatestate("")
        }
        delete("id": "delete${className}", "delete from ${table.getName()} where ${primaryColumn.colName}=#{id}")

    }
    insertSql = "insert into ${table.getName()}"
    updateSql = "update " + table.getName()
    updateState = "where ${primaryColumn.colName}=#{${pojoName}.${primaryColumn.name}}"
    out.println strXml.toString().replaceAll("fistate", "if").replaceAll("<insertsql></insertsql>", insertSql)
        .replaceAll("<updatesql></updatesql>", updateSql).replaceAll("<updatestate></updatestate>", updateState)
}

def generatePOFile(out, className, fields, packageName) {
    out.println "package $packageName"
    out.println ""
    out.println "import java.io.Serializable;"
    out.println "import lombok.Getter;"
    out.println "import lombok.Setter;"
    out.println "import lombok.ToString;"
    Set types = new HashSet()

    fields.each() {
        types.add(it.type)
    }

    if (types.contains("Date")) {
        out.println "import java.util.Date;"
    }

    if (types.contains("InputStream")) {
        out.println "import java.io.InputStream;"
    }
    out.println ""
    out.println "@Setter"
    out.println "@Getter"
    out.println "@ToString"
    out.println "public class ${className}PO implements Serializable {"
    out.println ""
    out.println genSerialID()
    fields.each() {
        out.println ""
        // 输出注释
        if (isNotEmpty(it.commoent)) {
            out.println "\t/**"
            out.println "\t * ${it.commoent.toString()}"
            out.println "\t */"
        }

        // 输出成员变量
        out.println "\tprivate ${it.type} ${it.name};"
    }

    out.println ""
    out.println "}"
}

def generateMapperFile(out, className, fields, packageName) {
    out.println "package $packageName"
    out.println ""
    out.println "import java.util.List;"
    out.println "import java.util.Map;"
    out.println "import org.apache.ibatis.annotations.Mapper;"
    out.println "import org.apache.ibatis.annotations.Param;"
    def pojoName = Case.LOWER.apply(className[0]) + className[1..-1]
    def primaryColumn = fields.find { it.annos.contains("@Id") }
    out.println ""
    out.println "@Mapper"
    out.println "public interface ${className}Mapper {"
    out.println ""
    out.println "    List<${className}PO> query${className}s(Map<String, Object> paramMap);"
    out.println ""
    out.println "    ${primaryColumn.type} insert${className}(@Param(\"${pojoName}PO\") ${className}PO ${pojoName}PO);"
    out.println ""
    out.println "    ${primaryColumn.type} update${className}(@Param(\"${pojoName}PO\") ${className}PO ${pojoName}PO);"
    out.println ""
    out.println "    ${className}PO query${className}ById(${primaryColumn.type} id);"
    out.println ""
    out.println "    void delete${className}ById(${primaryColumn.type} id);"
    out.println ""
    out.println "}"
}

def calcFields(table) {
    DasUtil.getColumns(table).reduce([]) { fields, col ->
        def spec = Case.LOWER.apply(col.getDataType().getSpecification())

        def typeStr = typeMapping.find { p, t -> p.matcher(spec).find() }.value
        def comm = [
            colName : col.getName(),
            name    : javaName(col.getName(), false),
            type    : typeStr,
            commoent: col.getComment(),
            annos   : "\t@Column(name = \"" + col.getName() + "\" )"]
        if ("id".equals(Case.LOWER.apply(col.getName())))
            comm.annos += ["@Id"]
        fields += [comm]
    }
}

// 处理类名（这里是因为我的表都是以t_命名的，所以需要处理去掉生成类名时的开头的T，
// 如果你不需要那么请查找用到了 javaClassName这个方法的地方修改为 javaName 即可）
def javaClassName(str, capitalize) {
    def s = com.intellij.psi.codeStyle.NameUtil.splitNameIntoWords(str)
        .collect { Case.LOWER.apply(it).capitalize() }
        .join("")
        .replaceAll(/[^\p{javaJavaIdentifierPart}[_]]/, "_")
    // 去除开头的T  http://developer.51cto.com/art/200906/129168.htm
    /*s = s[1..s.size() - 1]*/
    capitalize || s.length() == 1 ? s : Case.LOWER.apply(s[0]) + s[1..-1]
}

def javaName(str, capitalize) {

    def s = com.intellij.psi.codeStyle.NameUtil.splitNameIntoWords(str)
        .collect { Case.LOWER.apply(it).capitalize() }
        .join("")
        .replaceAll(/[^\p{javaJavaIdentifierPart}[_]]/, "_")
    capitalize || s.length() == 1 ? s : Case.LOWER.apply(s[0]) + s[1..-1]
}

def isNotEmpty(content) {
    return content != null && content.toString().trim().length() > 0
}

static String changeStyle(String str, boolean toCamel) {
    if (!str || str.size() <= 1)
        return str

    if (toCamel) {
        String r = str.toLowerCase().split('_').collect { cc -> Case.LOWER.apply(cc).capitalize() }.join('')
        return r[0].toLowerCase() + r[1..-1]
    } else {
        str = str[0].toLowerCase() + str[1..-1]
        return str.collect { cc -> ((char) cc).isUpperCase() ? '_' + cc.toLowerCase() : cc }.join('')
    }
}

static String genSerialID() {
    return "\tprivate static final long serialVersionUID =  " + Math.abs(new Random().nextLong()) + "L;"
}
