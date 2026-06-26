// bulk_renamer.java
import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.*;
import java.util.regex.*;
import java.text.SimpleDateFormat;

public class bulk_renamer {
    private static final String RESET = "\u001B[0m";
    private static final String GREEN = "\u001B[92m";
    private static final String RED = "\u001B[91m";
    private static final String YELLOW = "\u001B[93m";
    private static final String BLUE = "\u001B[94m";

    private static String colorize(String text, String color) {
        return color + text + RESET;
    }

    private static List<Path> getFiles(Path root, boolean recursive, List<String> extensions, String filterRegex) throws IOException {
        List<Path> files = new ArrayList<>();
        if (!Files.exists(root)) return files;

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(root)) {
            for (Path entry : stream) {
                if (Files.isDirectory(entry)) {
                    if (recursive) files.addAll(getFiles(entry, recursive, extensions, filterRegex));
                    continue;
                }
                String name = entry.getFileName().toString();
                if (extensions != null && !extensions.isEmpty()) {
                    String ext = "";
                    int dot = name.lastIndexOf('.');
                    if (dot > 0) ext = name.substring(dot+1);
                    if (!extensions.contains(ext)) continue;
                }
                if (filterRegex != null && !filterRegex.isEmpty()) {
                    if (!Pattern.compile(filterRegex).matcher(name).find()) continue;
                }
                files.add(entry);
            }
        }
        return files;
    }

    private static String applyTemplate(String template, String name, String ext, int num,
                                        String prefix, String suffix, String replaceFrom, String replaceTo, String caseType) {
        String newName = name;
        if (replaceFrom != null && !replaceFrom.isEmpty()) {
            newName = newName.replaceAll(replaceFrom, replaceTo);
        }
        if (caseType != null) {
            if (caseType.equalsIgnoreCase("lower")) newName = newName.toLowerCase();
            else if (caseType.equalsIgnoreCase("upper")) newName = newName.toUpperCase();
            else if (caseType.equalsIgnoreCase("title")) {
                StringBuilder sb = new StringBuilder();
                boolean cap = true;
                for (char c : newName.toCharArray()) {
                    if (Character.isWhitespace(c)) cap = true;
                    else if (cap) { sb.append(Character.toTitleCase(c)); cap = false; }
                    else sb.append(c);
                }
                newName = sb.toString();
            }
        }
        if (prefix != null) newName = prefix + newName;
        if (suffix != null) newName = newName + suffix;

        // Replace variables
        String result = template;
        Pattern varPattern = Pattern.compile("\\{([^{}]+)\\}");
        Matcher m = varPattern.matcher(result);
        while (m.find()) {
            String key = m.group(1);
            String replacement = "";
            if (key.equals("name")) replacement = newName;
            else if (key.equals("ext")) replacement = ext;
            else if (key.equals("old")) replacement = name;
            else if (key.equals("date")) replacement = new SimpleDateFormat("yyyy-MM-dd").format(new Date());
            else if (key.startsWith("num")) {
                if (key.contains(":")) {
                    String fmt = key.split(":")[1];
                    replacement = String.format("%" + fmt, num);
                } else {
                    replacement = Integer.toString(num);
                }
            } else {
                replacement = m.group(0);
            }
            result = result.replace(m.group(0), replacement);
        }
        return result;
    }

    public static void main(String[] args) throws IOException {
        Map<String, String> opts = new HashMap<>();
        boolean recursive = false, dryRun = false, verbose = false;
        int startNum = 1;

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (arg.equals("-t") && i+1 < args.length) opts.put("template", args[++i]);
            else if (arg.equals("-p") && i+1 < args.length) opts.put("path", args[++i]);
            else if (arg.equals("-r")) recursive = true;
            else if (arg.equals("-e") && i+1 < args.length) opts.put("ext", args[++i]);
            else if (arg.equals("-f") && i+1 < args.length) opts.put("filter", args[++i]);
            else if (arg.equals("--prefix") && i+1 < args.length) opts.put("prefix", args[++i]);
            else if (arg.equals("--suffix") && i+1 < args.length) opts.put("suffix", args[++i]);
            else if (arg.equals("--replace-from") && i+1 < args.length) opts.put("replace-from", args[++i]);
            else if (arg.equals("--replace-to") && i+1 < args.length) opts.put("replace-to", args[++i]);
            else if (arg.equals("--case") && i+1 < args.length) opts.put("case", args[++i]);
            else if (arg.equals("--number") && i+1 < args.length) startNum = Integer.parseInt(args[++i]);
            else if (arg.equals("--dry-run")) dryRun = true;
            else if (arg.equals("-v")) verbose = true;
            else if (arg.equals("--help")) { System.out.println("Help..."); return; }
        }

        String template = opts.getOrDefault("template", "");
        if (template.isEmpty()) {
            System.out.println(colorize("Error: template required (-t)", RED));
            return;
        }

        String rootPath = opts.getOrDefault("path", ".");
        Path root = Paths.get(rootPath);
        String extStr = opts.getOrDefault("ext", "");
        String filter = opts.getOrDefault("filter", "");
        String prefix = opts.getOrDefault("prefix", "");
        String suffix = opts.getOrDefault("suffix", "");
        String replaceFrom = opts.getOrDefault("replace-from", null);
        String replaceTo = opts.getOrDefault("replace-to", "");
        String caseType = opts.getOrDefault("case", null);

        List<String> extensions = null;
        if (!extStr.isEmpty()) extensions = Arrays.asList(extStr.split(","));

        List<Path> files = getFiles(root, recursive, extensions, filter);
        if (files.isEmpty()) {
            System.out.println(colorize("No files found.", YELLOW));
            return;
        }

        int numCounter = startNum;
        int renamed = 0;

        for (Path fullPath : files) {
            String oldName = fullPath.getFileName().toString();
            String ext = "";
            int dot = oldName.lastIndexOf('.');
            if (dot > 0) {
                ext = oldName.substring(dot+1);
            }
            String nameWithoutExt = dot > 0 ? oldName.substring(0, dot) : oldName;

            String newName = applyTemplate(template, nameWithoutExt, ext, numCounter,
                                           prefix, suffix, replaceFrom, replaceTo, caseType);
            numCounter++;

            if (!template.contains("{ext}") && !ext.isEmpty()) {
                newName = newName + "." + ext;
            }

            Path newPath = fullPath.resolveSibling(newName);

            if (dryRun || verbose) {
                System.out.println(colorize("->", BLUE) + " " + oldName + " -> " + newName);
            }

            if (!dryRun) {
                // Conflict resolution
                if (Files.exists(newPath) && !newPath.equals(fullPath)) {
                    String baseName = newName;
                    int dot2 = newName.lastIndexOf('.');
                    if (dot2 > 0) baseName = newName.substring(0, dot2);
                    String ext2 = dot2 > 0 ? newName.substring(dot2) : "";
                    int counter = 1;
                    Path tryPath;
                    do {
                        tryPath = fullPath.resolveSibling(baseName + "_" + counter + ext2);
                        counter++;
                    } while (Files.exists(tryPath));
                    newPath = tryPath;
                    if (verbose) {
                        System.out.println(colorize("  Conflict, new name: " + newPath.getFileName().toString(), YELLOW));
                    }
                }

                try {
                    Files.move(fullPath, newPath, StandardCopyOption.REPLACE_EXISTING);
                    renamed++;
                } catch (IOException e) {
                    System.out.println(colorize("Error renaming " + oldName + ": " + e.getMessage(), RED));
                }
            }
        }

        if (dryRun)
            System.out.println(colorize("Dry run completed. Would rename " + files.size() + " files.", GREEN));
        else
            System.out.println(colorize("Renamed " + renamed + " files.", GREEN));
    }
}
