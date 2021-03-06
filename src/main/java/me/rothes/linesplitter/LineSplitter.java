package me.rothes.linesplitter;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.ansj.domain.Term;
import org.ansj.splitWord.analysis.NlpAnalysis;
import org.apache.commons.collections.map.ListOrderedMap;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Scanner;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class LineSplitter {

    private static Pattern messagePattern = Pattern.compile("^\\\\[A-Z0-9]{2}(\\*)?");
    private static Pattern enPattern = Pattern.compile("[a-zA-Z0-9|!@#$%^&*()\\[\\],./:;?<>\"' ]");

    private static Pattern formats1 = Pattern.compile("\\\\[A-Za-z0-9]{2}");
    private static Pattern formats2 = Pattern.compile("[~^][1-9]");
    private static Pattern suffix = Pattern.compile("/(%)?$");

    private static Pattern colorFix = Pattern.compile("([ ]*\\\\[A-Za-z0-9]{2})([^&#]*)([&#][ ]*)([^\\\\]*)(\\\\[A-Za-z0-9]{2})");

    private enum MessageType {
        TALK,
        ACCESS
    }

    public static void main(String[] args) {
        Scanner sc = new Scanner(System.in);
        System.out.println("输入 json 路径:");
        File file = new File(sc.nextLine());
        if (!file.exists()) {
            System.out.println("文件不存在: " + file.getAbsolutePath());
            sleep(10000L);
            return;
        }
        String json = readFile(file);
        sc.close();
        System.out.println("开始处理, 请稍等...");

        // 修改 json
        JsonElement jsonElement = JsonParser.parseString(json);
        JsonObject jsonObject = jsonElement.getAsJsonObject();

        Set<String> keySet = jsonObject.keySet();
        for (String key : keySet) {
            jsonObject.addProperty(key, process(key, jsonObject.getAsJsonPrimitive(key).getAsString()));
        }

        // 保存文件
        try {
            String result = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping() /* 格式化 */
                    .create().toJson(jsonElement);
            BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8));
            writer.write(result);
            writer.close();
        } catch (IOException e) {
            e.printStackTrace();
        }

        System.out.println("已完成处理.");
        sleep(15000L);
    }

    private static String process(String key, String str) {
        MessageType type;
        String prefix;
        Matcher matcher = messagePattern.matcher(str);
        if (str.contains("#")) {
            return str;
        } else if (str.startsWith("*") || str.startsWith(" *")) {
            prefix = " ";
            type = MessageType.ACCESS;
        } else if (matcher.find()) {
            prefix = "".equals(matcher.group(1)) ? null : " ";
            type = MessageType.TALK;
        } else {
            return str;
        }

        String result = str;
        try {
            result = getSplited(str, type == MessageType.ACCESS ? 39 : 30, (key.contains("menu") || key.contains("info")) ? "#" : "&", prefix);
        } catch (StackOverflowError e) {
            System.out.println("\033[0;91m无法处理此key，颜色间字符长度过长，请手动在插入换行: \033[0m" + key);
        }
        if (result.split("&").length > 3) {
            System.out.println("\033[0;91m警告, 此 key 换行达到三次: \033[0m" + key);
        }
        return result;
    }

    private static String getSplited(String str, int weigh, String newLine, String prefix) {
        String[] split = str.split(newLine);
        int array = 0;
        String edited = null;
        OUT:
        for (; array < split.length; array++) {
            String s = split[array];
            // 移除 文字效果、占位符，之后重新添加
            StringBuilder builder = new StringBuilder(s);
            String suf = "";
            Matcher matcher = suffix.matcher(builder.toString());
            if (matcher.find()) {
                suf = matcher.group(0);
                builder.delete(builder.length() - suf.length(), builder.length());
            }

            ListOrderedMap fm1 = new ListOrderedMap();
            matcher = formats1.matcher(s);
            while (matcher.find()) {
                String matched = matcher.group(0);
                fm1.put(matcher.end() - matched.length(), matched);
            }
            deletePlaceholders(builder, fm1);
            ListOrderedMap fm2 = new ListOrderedMap();
            matcher = formats2.matcher(builder.toString());
            while (matcher.find()) {
                String matched = matcher.group(0);
                fm2.put(matcher.end() - matched.length(), matched);
            }
            deletePlaceholders(builder, fm2);

            // 开始处理
            String toParse = builder.toString();
            int score = getWidthScore(toParse);
            if (score > weigh) {
                List<Term> terms = NlpAnalysis.parse(toParse).getTerms();
                for (Term term : terms) {
                    int length = term.getOffe() + term.getName().length();
                    if (length < toParse.length() && getWidthScore(toParse.substring(0, length)) > weigh) {
                        if (prefix != null) {
                            if (builder.charAt(term.getOffe()) == ' ') {
                                builder.insert(term.getOffe(), newLine + prefix);
                                addPlaceholders(builder, fm1, addPlaceholders(builder, fm2, term.getOffe(), ("&" + prefix).length()), ("&" + prefix).length());
                            } else {
                                builder.insert(term.getOffe(), newLine + " " + prefix);
                                addPlaceholders(builder, fm1, addPlaceholders(builder, fm2, term.getOffe(), ("& " + prefix).length()), ("& " + prefix).length());
                            }
                        } else {
                            builder.insert(term.getOffe(), newLine);
                            addPlaceholders(builder, fm1, addPlaceholders(builder, fm2, term.getOffe(), 1), 1);
                        }
                        edited = builder.append(suf).toString();
                        break OUT;
                    }
                }
            }
        }

        // 处理结果
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < split.length; i++) {
            if (array == i && edited != null) {
                builder.append(edited);
            } else {
                builder.append(split[i]);
            }
            builder.append(newLine);
        }
        if (builder.length() > 0 && builder.charAt(builder.length() - 1) == newLine.indexOf(0)) {
            builder.deleteCharAt(builder.length() - 1);
        }
        if (edited == null) {
            return builder.toString();
        } else {
            return getSplited(fixColor(builder.toString()), weigh, newLine, prefix);
        }
    }

    private static int getWidthScore(String str) {
        int score = 0;
        for (int i = 0; i < str.length(); i++) {
            char c = str.charAt(i);
            if (enPattern.matcher(String.valueOf(c)).matches()) {
                score += 1;
            } else {
                score += 2;
            }
        }
        return score;
    }

    private static String fixColor(String str) {
        Matcher matcher = colorFix.matcher(str);
        while (matcher.find()) {
            String plain = matcher.group(1).substring(matcher.group(1).length() - 3);
            if (plain.equals(matcher.group(5)) && (matcher.group(3).startsWith("&") || matcher.group(3).startsWith("#"))) {
                return fixColor(str.replace(matcher.group(0), matcher.group(3) + plain + matcher.group(2) + matcher.group(4) + matcher.group(5)));
            }
        }
        return str;
    }

    @SuppressWarnings("unchecked")
    private static void deletePlaceholders(StringBuilder builder, ListOrderedMap map) {
        List<Integer> list = (List<Integer>) map.keyList();
        for (int i = list.size() - 1; i >= 0; i--) {
            Integer key = list.get(i);
            String ph = (String) map.get(key);
            for (int i1 = 0; i1 < ph.length(); i1++) {
                builder.deleteCharAt(key);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static int addPlaceholders(StringBuilder builder, ListOrderedMap map, int insertIndex, int insertLength) {
        List<Integer> list = (List<Integer>) map.keyList();
        int result = insertIndex;
        for (Integer key : list) {
            String ph = (String) map.get(key);
            if (key > insertIndex) {
                builder.insert(key + insertLength, ph);
                result += ph.length();
            } else {
                builder.insert(key, ph);
            }
        }
        return result;
    }

    private static String readFile(File file) {
        final StringBuilder builder = new StringBuilder();
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8));
            for (String line = reader.readLine(); line != null; line = reader.readLine()) {
                builder.append(line).append("\n");
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return builder.toString();
    }

    private static void sleep(long time) {
        try {
            Thread.sleep(time);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

}
