// bulk_renamer.go
package main

import (
	"flag"
	"fmt"
	"os"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
	"time"
)

const (
	reset  = "\033[0m"
	green  = "\033[92m"
	red    = "\033[91m"
	yellow = "\033[93m"
	blue   = "\033[94m"
)

func colorize(text, color string) string {
	return color + text + reset
}

func getFiles(root string, recursive bool, extensions []string, filterRegex string) ([]string, error) {
	var files []string
	err := filepath.Walk(root, func(path string, info os.FileInfo, err error) error {
		if err != nil {
			return err
		}
		if info.IsDir() {
			if !recursive && path != root {
				return filepath.SkipDir
			}
			return nil
		}
		if extensions != nil {
			found := false
			for _, ext := range extensions {
				if strings.HasSuffix(info.Name(), "."+ext) {
					found = true
					break
				}
			}
			if !found {
				return nil
			}
		}
		if filterRegex != "" {
			matched, err := regexp.MatchString(filterRegex, info.Name())
			if err != nil {
				return err
			}
			if !matched {
				return nil
			}
		}
		files = append(files, path)
		return nil
	})
	return files, err
}

func applyTemplate(template, name, ext string, num int, prefix, suffix, replaceFrom, replaceTo, caseType string) string {
	newName := name
	if replaceFrom != "" {
		re := regexp.MustCompile(replaceFrom)
		newName = re.ReplaceAllString(newName, replaceTo)
	}
	switch caseType {
	case "lower":
		newName = strings.ToLower(newName)
	case "upper":
		newName = strings.ToUpper(newName)
	case "title":
		newName = strings.Title(newName)
	}
	if prefix != "" {
		newName = prefix + newName
	}
	if suffix != "" {
		newName = newName + suffix
	}

	// Замена переменных в шаблоне
	result := template
	re := regexp.MustCompile(`\{([^{}]+)\}`)
	result = re.ReplaceAllStringFunc(result, func(match string) string {
		key := strings.Trim(match, "{}")
		if key == "name" {
			return newName
		}
		if key == "ext" {
			return ext
		}
		if key == "old" {
			return name
		}
		if key == "date" {
			return time.Now().Format("2006-01-02")
		}
		if strings.HasPrefix(key, "num") {
			// поддерживаем формат {num:03d}
			if strings.Contains(key, ":") {
				parts := strings.SplitN(key, ":", 2)
				fmtStr := parts[1]
				return fmt.Sprintf(fmtStr, num)
			}
			return strconv.Itoa(num)
		}
		return match
	})
	return result
}

func main() {
	var (
		template   string
		path       string
		recursive  bool
		extStr     string
		filter     string
		prefix     string
		suffix     string
		replaceFrom string
		replaceTo   string
		caseType   string
		startNum   int
		dryRun     bool
		verbose    bool
	)
	flag.StringVar(&template, "t", "", "Шаблон нового имени")
	flag.StringVar(&path, "p", ".", "Путь к папке")
	flag.BoolVar(&recursive, "r", false, "Рекурсивно")
	flag.StringVar(&extStr, "e", "", "Фильтр по расширению (через запятую)")
	flag.StringVar(&filter, "f", "", "Фильтр по имени (regex)")
	flag.StringVar(&prefix, "prefix", "", "Префикс")
	flag.StringVar(&suffix, "suffix", "", "Суффикс")
	flag.StringVar(&replaceFrom, "replace-from", "", "Заменить от")
	flag.StringVar(&replaceTo, "replace-to", "", "Заменить на")
	flag.StringVar(&caseType, "case", "", "Регистр: lower, upper, title")
	flag.IntVar(&startNum, "number", 0, "Начать нумерацию с этого числа")
	flag.BoolVar(&dryRun, "dry-run", false, "Симуляция")
	flag.BoolVar(&verbose, "v", false, "Подробно")
	flag.Parse()

	if template == "" {
		fmt.Println(colorize("Ошибка: укажите шаблон (-t)", red))
		os.Exit(1)
	}

	var extensions []string
	if extStr != "" {
		extensions = strings.Split(extStr, ",")
	}

	files, err := getFiles(path, recursive, extensions, filter)
	if err != nil {
		fmt.Println(colorize("Ошибка: "+err.Error(), red))
		os.Exit(1)
	}

	if len(files) == 0 {
		fmt.Println(colorize("Нет файлов.", yellow))
		return
	}

	numCounter := startNum
	if numCounter == 0 {
		numCounter = 1
	}
	renamed := 0

	for _, fullPath := range files {
		dir := filepath.Dir(fullPath)
		oldName := filepath.Base(fullPath)
		ext := filepath.Ext(oldName)
		nameWithoutExt := strings.TrimSuffix(oldName, ext)
		ext = strings.TrimPrefix(ext, ".")

		newName := applyTemplate(template, nameWithoutExt, ext, numCounter, prefix, suffix, replaceFrom, replaceTo, caseType)
		numCounter++

		// Если расширение не было вставлено, добавляем
		if !strings.Contains(template, "{ext}") && ext != "" {
			newName = newName + "." + ext
		}

		newPath := filepath.Join(dir, newName)

		if dryRun || verbose {
			fmt.Printf("%s %s -> %s\n", colorize("->", blue), oldName, newName)
		}

		if !dryRun {
			// Проверка конфликта
			if _, err := os.Stat(newPath); err == nil && newPath != fullPath {
				// Добавляем суффикс
				base := strings.TrimSuffix(newName, filepath.Ext(newName))
				ext2 := filepath.Ext(newName)
				counter := 1
				for {
					tryPath := filepath.Join(dir, fmt.Sprintf("%s_%d%s", base, counter, ext2))
					if _, err := os.Stat(tryPath); os.IsNotExist(err) {
						newPath = tryPath
						break
					}
					counter++
				}
				if verbose {
					fmt.Println(colorize("  Конфликт, новое имя: "+filepath.Base(newPath), yellow))
				}
			}

			err := os.Rename(fullPath, newPath)
			if err != nil {
				fmt.Println(colorize("Ошибка при переименовании "+oldName+": "+err.Error(), red))
			} else {
				renamed++
			}
		}
	}

	if dryRun {
		fmt.Println(colorize(fmt.Sprintf("Симуляция завершена. Будет переименовано %d файлов.", len(files)), green))
	} else {
		fmt.Println(colorize(fmt.Sprintf("Переименовано %d файлов.", renamed), green))
	}
}
