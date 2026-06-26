// bulk_renamer.cpp
#include <iostream>
#include <string>
#include <vector>
#include <filesystem>
#include <regex>
#include <algorithm>
#include <ctime>
#include <iomanip>
#include <sstream>

using namespace std;
namespace fs = std::filesystem;

const string RESET = "\033[0m";
const string GREEN = "\033[92m";
const string RED = "\033[91m";
const string YELLOW = "\033[93m";
const string BLUE = "\033[94m";

string colorize(const string& text, const string& color) {
    return color + text + RESET;
}

vector<fs::path> getFiles(const fs::path& root, bool recursive, const vector<string>& extensions, const string& filterRegex) {
    vector<fs::path> files;
    if (!fs::exists(root)) return files;

    function<void(const fs::path&)> walk = [&](const fs::path& dir) {
        for (const auto& entry : fs::directory_iterator(dir)) {
            if (entry.is_directory()) {
                if (recursive) walk(entry.path());
                continue;
            }
            if (!extensions.empty()) {
                string ext = entry.path().extension().string();
                if (!ext.empty()) ext = ext.substr(1);
                if (find(extensions.begin(), extensions.end(), ext) == extensions.end())
                    continue;
            }
            if (!filterRegex.empty()) {
                regex re(filterRegex);
                if (!regex_search(entry.path().filename().string(), re))
                    continue;
            }
            files.push_back(entry.path());
        }
    };
    walk(root);
    return files;
}

string replaceVariables(const string& templateStr, const string& name, const string& ext, int num,
                        const string& prefix, const string& suffix,
                        const string& replaceFrom, const string& replaceTo,
                        const string& caseType) {
    string newName = name;
    if (!replaceFrom.empty()) {
        regex re(replaceFrom);
        newName = regex_replace(newName, re, replaceTo);
    }
    if (caseType == "lower") {
        transform(newName.begin(), newName.end(), newName.begin(), ::tolower);
    } else if (caseType == "upper") {
        transform(newName.begin(), newName.end(), newName.begin(), ::toupper);
    } else if (caseType == "title") {
        bool cap = true;
        for (char& c : newName) {
            if (isspace(c)) cap = true;
            else if (cap) { c = toupper(c); cap = false; }
        }
    }
    if (!prefix.empty()) newName = prefix + newName;
    if (!suffix.empty()) newName = newName + suffix;

    // Replace variables
    string result = templateStr;
    regex var_regex(R"(\{([^{}]+)\})");
    smatch match;
    while (regex_search(result, match, var_regex)) {
        string key = match[1].str();
        string replacement;
        if (key == "name") replacement = newName;
        else if (key == "ext") replacement = ext;
        else if (key == "old") replacement = name;
        else if (key == "date") {
            time_t t = time(nullptr);
            tm* now = localtime(&t);
            char buf[11];
            strftime(buf, sizeof(buf), "%Y-%m-%d", now);
            replacement = buf;
        } else if (key.find("num") == 0) {
            if (key.find(":") != string::npos) {
                string fmt = key.substr(key.find(":")+1);
                char buf[20];
                snprintf(buf, sizeof(buf), fmt.c_str(), num);
                replacement = buf;
            } else {
                replacement = to_string(num);
            }
        } else {
            replacement = match[0].str();
        }
        result.replace(match.position(), match.length(), replacement);
    }
    return result;
}

int main(int argc, char* argv[]) {
    if (argc < 2) {
        cout << colorize("Usage: bulk_renamer -t <template> [options]", YELLOW) << endl;
        cout << "Options: -p path, -r recursive, -e ext1,ext2, -f regex, --prefix, --suffix, --replace-from, --replace-to, --case, --number, --dry-run, -v" << endl;
        return 1;
    }

    string templateStr, path = ".", extStr, filter, prefix, suffix, replaceFrom, replaceTo, caseType;
    bool recursive = false, dryRun = false, verbose = false;
    int startNum = 1;

    for (int i = 1; i < argc; ++i) {
        string arg = argv[i];
        if (arg == "-t" && i+1 < argc) templateStr = argv[++i];
        else if (arg == "-p" && i+1 < argc) path = argv[++i];
        else if (arg == "-r") recursive = true;
        else if (arg == "-e" && i+1 < argc) extStr = argv[++i];
        else if (arg == "-f" && i+1 < argc) filter = argv[++i];
        else if (arg == "--prefix" && i+1 < argc) prefix = argv[++i];
        else if (arg == "--suffix" && i+1 < argc) suffix = argv[++i];
        else if (arg == "--replace-from" && i+1 < argc) replaceFrom = argv[++i];
        else if (arg == "--replace-to" && i+1 < argc) replaceTo = argv[++i];
        else if (arg == "--case" && i+1 < argc) caseType = argv[++i];
        else if (arg == "--number" && i+1 < argc) startNum = stoi(argv[++i]);
        else if (arg == "--dry-run") dryRun = true;
        else if (arg == "-v") verbose = true;
        else if (arg == "--help") { /* show help */ return 0; }
    }

    if (templateStr.empty()) {
        cout << colorize("Error: template required (-t)", RED) << endl;
        return 1;
    }

    vector<string> extensions;
    if (!extStr.empty()) {
        stringstream ss(extStr);
        string ext;
        while (getline(ss, ext, ',')) {
            if (!ext.empty()) extensions.push_back(ext);
        }
    }

    auto files = getFiles(path, recursive, extensions, filter);
    if (files.empty()) {
        cout << colorize("No files found.", YELLOW) << endl;
        return 0;
    }

    int numCounter = startNum;
    int renamed = 0;

    for (const auto& file : files) {
        string oldName = file.filename().string();
        string ext = file.extension().string();
        if (!ext.empty()) ext = ext.substr(1);
        string nameWithoutExt = file.stem().string();

        string newName = replaceVariables(templateStr, nameWithoutExt, ext, numCounter,
                                         prefix, suffix, replaceFrom, replaceTo, caseType);
        numCounter++;

        if (templateStr.find("{ext}") == string::npos && !ext.empty()) {
            newName = newName + "." + ext;
        }

        fs::path newPath = file.parent_path() / newName;

        if (dryRun || verbose) {
            cout << colorize("->", BLUE) << " " << oldName << " -> " << newName << endl;
        }

        if (!dryRun) {
            // Conflict resolution
            if (fs::exists(newPath) && newPath != file) {
                string base = newPath.stem().string();
                string ext2 = newPath.extension().string();
                int counter = 1;
                fs::path tryPath;
                do {
                    tryPath = file.parent_path() / (base + "_" + to_string(counter) + ext2);
                    counter++;
                } while (fs::exists(tryPath));
                newPath = tryPath;
                if (verbose) {
                    cout << colorize("  Conflict, new name: " + newPath.filename().string(), YELLOW) << endl;
                }
            }

            try {
                fs::rename(file, newPath);
                renamed++;
            } catch (const exception& e) {
                cout << colorize("Error renaming " + oldName + ": " + e.what(), RED) << endl;
            }
        }
    }

    if (dryRun) {
        cout << colorize("Dry run completed. Would rename " + to_string(files.size()) + " files.", GREEN) << endl;
    } else {
        cout << colorize("Renamed " + to_string(renamed) + " files.", GREEN) << endl;
    }
    return 0;
}
