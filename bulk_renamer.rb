#!/usr/bin/env ruby
# bulk_renamer.rb
# encoding: UTF-8

require 'optparse'
require 'pathname'
require 'date'

COLORS = {
  green: "\e[92m",
  red: "\e[91m",
  yellow: "\e[93m",
  blue: "\e[94m",
  reset: "\e[0m"
}

def colorize(text, color)
  "#{COLORS[color]}#{text}#{COLORS[:reset]}"
end

def get_files(root, recursive, extensions, filter_regex)
  files = []
  root = Pathname.new(root)
  return files unless root.exist?

  pattern = recursive ? '**/*' : '*'
  root.glob(pattern).each do |entry|
    next unless entry.file?
    name = entry.basename.to_s
    if extensions && !extensions.empty?
      ext = entry.extname[1..-1] || ''
      next unless extensions.include?(ext.downcase)
    end
    if filter_regex
      next unless name.match?(Regexp.new(filter_regex))
    end
    files << entry
  end
  files
end

def apply_template(template, name, ext, num, prefix, suffix, replace_from, replace_to, case_type)
  new_name = name.dup
  if replace_from && replace_to
    new_name.gsub!(Regexp.new(replace_from), replace_to)
  end
  case case_type
  when 'lower' then new_name.downcase!
  when 'upper' then new_name.upcase!
  when 'title' then new_name.gsub!(/\b\w/, &:capitalize)
  end
  new_name = prefix + new_name if prefix
  new_name = new_name + suffix if suffix

  result = template.dup
  result.gsub!(/\{([^{}]+)\}/) do |match|
    key = $1
    case key
    when 'name' then new_name
    when 'ext' then ext || ''
    when 'old' then name
    when 'date' then Date.today.strftime('%Y-%m-%d')
    when /^num/
      if key.include?(':')
        fmt = key.split(':')[1]
        sprintf("%#{fmt}", num)
      else
        num.to_s
      end
    else match
    end
  end
  result
end

options = {
  path: '.',
  recursive: false,
  extensions: nil,
  filter: nil,
  prefix: nil,
  suffix: nil,
  replace_from: nil,
  replace_to: nil,
  case_type: nil,
  start_num: 1,
  dry_run: false,
  verbose: false
}

parser = OptionParser.new do |opts|
  opts.banner = "Usage: bulk_renamer.rb -t <template> [options]"
  opts.on("-t", "--template TEMPLATE", "Шаблон") { |v| options[:template] = v }
  opts.on("-p", "--path PATH", "Путь") { |v| options[:path] = v }
  opts.on("-r", "--recursive", "Рекурсивно") { options[:recursive] = true }
  opts.on("-e", "--ext EXT1,EXT2", "Расширения") { |v| options[:extensions] = v.split(',').map(&:strip) }
  opts.on("-f", "--filter REGEX", "Фильтр") { |v| options[:filter] = v }
  opts.on("--prefix TEXT", "Префикс") { |v| options[:prefix] = v }
  opts.on("--suffix TEXT", "Суффикс") { |v| options[:suffix] = v }
  opts.on("--replace-from FROM", "Заменить от") { |v| options[:replace_from] = v }
  opts.on("--replace-to TO", "Заменить на") { |v| options[:replace_to] = v }
  opts.on("--case lower/upper/title", "Регистр") { |v| options[:case_type] = v }
  opts.on("--number N", Integer, "Начать нумерацию") { |v| options[:start_num] = v }
  opts.on("--dry-run", "Симуляция") { options[:dry_run] = true }
  opts.on("-v", "--verbose", "Подробно") { options[:verbose] = true }
  opts.on("-h", "--help", "Справка") { puts opts; exit }
end
parser.parse!

unless options[:template]
  puts colorize("Error: template required (-t)", :red)
  exit 1
end

files = get_files(options[:path], options[:recursive], options[:extensions], options[:filter])
if files.empty?
  puts colorize("No files found.", :yellow)
  exit 0
end

num_counter = options[:start_num]
renamed = 0

files.each do |full_path|
  old_name = full_path.basename.to_s
  ext = full_path.extname[1..-1] || ''
  name_without_ext = full_path.basename('.*').to_s

  new_name = apply_template(options[:template], name_without_ext, ext, num_counter,
                            options[:prefix], options[:suffix],
                            options[:replace_from], options[:replace_to],
                            options[:case_type])
  num_counter += 1

  unless options[:template].include?('{ext}')
    new_name = new_name + '.' + ext unless ext.empty?
  end

  new_path = full_path.dirname + new_name

  if options[:dry_run] || options[:verbose]
    puts "#{colorize('->', :blue)} #{old_name} -> #{new_name}"
  end

  unless options[:dry_run]
    # Conflict resolution
    if new_path.exist? && new_path != full_path
      base = new_path.basename('.*').to_s
      ext2 = new_path.extname
      counter = 1
      while true
        try_path = full_path.dirname + "#{base}_#{counter}#{ext2}"
        break unless try_path.exist?
        counter += 1
      end
      new_path = try_path
      if options[:verbose]
        puts colorize("  Conflict, new name: #{new_path.basename}", :yellow)
      end
    end

    begin
      File.rename(full_path, new_path)
      renamed += 1
    rescue => e
      puts colorize("Error renaming #{old_name}: #{e.message}", :red)
    end
  end
end

if options[:dry_run]
  puts colorize("Dry run completed. Would rename #{files.size} files.", :green)
else
  puts colorize("Renamed #{renamed} files.", :green)
end
