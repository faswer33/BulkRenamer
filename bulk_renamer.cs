// bulk_renamer.cs
using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Text.RegularExpressions;

class BulkRenamer
{
    static string Colorize(string text, string color)
    {
        string col = color switch
        {
            "green" => "\x1b[92m",
            "red" => "\x1b[91m",
            "yellow" => "\x1b[93m",
            "blue" => "\x1b[94m",
            _ => "\x1b[0m"
        };
        return col + text + "\x1b[0m";
    }

    static List<string> GetFiles(string root, bool recursive, List<string> extensions, string filterRegex)
    {
        var files = new List<string>();
        var dir = new DirectoryInfo(root);
        if (!dir.Exists) return files;

        var options = recursive ? SearchOption.AllDirectories : SearchOption.TopDirectoryOnly;
        foreach (var file in dir.GetFiles("*", options))
        {
            if (extensions != null && extensions.Any())
            {
                if (!extensions.Contains(file.Extension.TrimStart('.'), StringComparer.OrdinalIgnoreCase))
                    continue;
            }
            if (!string.IsNullOrEmpty(filterRegex))
            {
                if (!Regex.IsMatch(file.Name, filterRegex))
                    continue;
            }
            files.Add(file.FullName);
        }
        return files;
    }

    static string ApplyTemplate(string templateStr, string name, string ext, int num,
                                string prefix, string suffix, string replaceFrom, string replaceTo, string caseType)
    {
        string newName = name;
        if (!string.IsNullOrEmpty(replaceFrom))
        {
            newName = Regex.Replace(newName, replaceFrom, replaceTo);
        }
        switch (caseType?.ToLower())
        {
            case "lower": newName = newName.ToLower(); break;
            case "upper": newName = newName.ToUpper(); break;
            case "title": newName = System.Globalization.CultureInfo.CurrentCulture.TextInfo.ToTitleCase(newName); break;
        }
        if (!string.IsNullOrEmpty(prefix)) newName = prefix + newName;
        if (!string.IsNullOrEmpty(suffix)) newName = newName + suffix;

        // Replace variables
        string result = templateStr;
        var matches = Regex.Matches(result, @"\{([^{}]+)\}");
        foreach (Match m in matches)
        {
            string key = m.Groups[1].Value;
            string replacement = "";
            if (key == "name") replacement = newName;
            else if (key == "ext") replacement = ext;
            else if (key == "old") replacement = name;
            else if (key == "date") replacement = DateTime.Now.ToString("yyyy-MM-dd");
            else if (key.StartsWith("num"))
            {
                if (key.Contains(":"))
                {
                    string fmt = key.Split(':')[1];
                    replacement = num.ToString(fmt);
                }
                else
                {
                    replacement = num.ToString();
                }
            }
            else
            {
                replacement = m.Value;
            }
            result = result.Replace(m.Value, replacement);
        }
        return result;
    }

    static void Main(string[] args)
    {
        var opts = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
        bool recursive = false, dryRun = false, verbose = false;
        int startNum = 1;

        for (int i = 0; i < args.Length; i++)
        {
            string arg = args[i];
            if (arg == "-t" && i+1 < args.Length) opts["template"] = args[++i];
            else if (arg == "-p" && i+1 < args.Length) opts["path"] = args[++i];
            else if (arg == "-r") recursive = true;
            else if (arg == "-e" && i+1 < args.Length) opts["ext"] = args[++i];
            else if (arg == "-f" && i+1 < args.Length) opts["filter"] = args[++i];
            else if (arg == "--prefix" && i+1 < args.Length) opts["prefix"] = args[++i];
            else if (arg == "--suffix" && i+1 < args.Length) opts["suffix"] = args[++i];
            else if (arg == "--replace-from" && i+1 < args.Length) opts["replace-from"] = args[++i];
            else if (arg == "--replace-to" && i+1 < args.Length) opts["replace-to"] = args[++i];
            else if (arg == "--case" && i+1 < args.Length) opts["case"] = args[++i];
            else if (arg == "--number" && i+1 < args.Length) startNum = int.Parse(args[++i]);
            else if (arg == "--dry-run") dryRun = true;
            else if (arg == "-v") verbose = true;
            else if (arg == "--help") { Console.WriteLine("Help..."); return; }
        }

        string template = opts.GetValueOrDefault("template", "");
        if (string.IsNullOrEmpty(template))
        {
            Console.WriteLine(Colorize("Error: template required (-t)", "red"));
            return;
        }

        string root = opts.GetValueOrDefault("path", ".");
        string extStr = opts.GetValueOrDefault("ext", "");
        string filter = opts.GetValueOrDefault("filter", "");
        string prefix = opts.GetValueOrDefault("prefix", "");
        string suffix = opts.GetValueOrDefault("suffix", "");
        string replaceFrom = opts.GetValueOrDefault("replace-from", "");
        string replaceTo = opts.GetValueOrDefault("replace-to", "");
        string caseType = opts.GetValueOrDefault("case", "");

        List<string> extensions = null;
        if (!string.IsNullOrEmpty(extStr))
            extensions = extStr.Split(',').Select(s => s.Trim()).ToList();

        var files = GetFiles(root, recursive, extensions, filter);
        if (files.Count == 0)
        {
            Console.WriteLine(Colorize("No files found.", "yellow"));
            return;
        }

        int numCounter = startNum;
        int renamed = 0;

        foreach (var fullPath in files)
        {
            string dir = Path.GetDirectoryName(fullPath);
            string oldName = Path.GetFileName(fullPath);
            string ext = Path.GetExtension(oldName).TrimStart('.');
            string nameWithoutExt = Path.GetFileNameWithoutExtension(oldName);

            string newName = ApplyTemplate(template, nameWithoutExt, ext, numCounter,
                                           prefix, suffix, replaceFrom, replaceTo, caseType);
            numCounter++;

            if (!template.Contains("{ext}") && !string.IsNullOrEmpty(ext))
            {
                newName = newName + "." + ext;
            }

            string newPath = Path.Combine(dir, newName);

            if (dryRun || verbose)
            {
                Console.WriteLine($"{Colorize("->", "blue")} {oldName} -> {newName}");
            }

            if (!dryRun)
            {
                // Conflict resolution
                if (File.Exists(newPath) && newPath != fullPath)
                {
                    string baseName = Path.GetFileNameWithoutExtension(newName);
                    string ext2 = Path.GetExtension(newName);
                    int counter = 1;
                    string tryPath;
                    do
                    {
                        tryPath = Path.Combine(dir, $"{baseName}_{counter}{ext2}");
                        counter++;
                    } while (File.Exists(tryPath));
                    newPath = tryPath;
                    if (verbose)
                    {
                        Console.WriteLine(Colorize($"  Conflict, new name: {Path.GetFileName(newPath)}", "yellow"));
                    }
                }

                try
                {
                    File.Move(fullPath, newPath);
                    renamed++;
                }
                catch (Exception e)
                {
                    Console.WriteLine(Colorize($"Error renaming {oldName}: {e.Message}", "red"));
                }
            }
        }

        if (dryRun)
            Console.WriteLine(Colorize($"Dry run completed. Would rename {files.Count} files.", "green"));
        else
            Console.WriteLine(Colorize($"Renamed {renamed} files.", "green"));
    }
}
