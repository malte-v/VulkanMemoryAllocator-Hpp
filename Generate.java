import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class Generate {

    /**
     * Represents single sequence of #if - [#elif]... - [#else] - #endif statements.
     * It forms a tree structure which is used to check all active conditions at any point in source code.
     * E.g. structure field is declared only if some condition is met, and we want to reflect it in our generated code.
     */
    static class Ifdef {
        final Range parent;
        final int start;
        final List<Range> ranges = new ArrayList<>();
        int end;
        boolean skip = false;
        Ifdef(Range parent, int start) {
            this.parent = parent;
            this.start = start;
            if (parent != null) parent.ifdef.add(this);
        }

        Range goTo(StringBuilder content, int index, int fromRange) {
            for (int i = fromRange; i < ranges.size(); i++) {
                Range r = ranges.get(i);
                if (!skip) content.append("\n#").append(r.text);
                if (index >= r.start && index < r.end) return r.goTo(content, index);
            }
            if (parent != null) {
                if (!skip) content.append("\n#endif");
                return parent.goTo(content, index);
            } else return null;
        }

        void skipLevels(int levels) {
            if (levels == 0) return;
            skip = true;
            if (levels == 1) return;
            for (Range r : ranges) {
                for (Ifdef d : r.ifdef) d.skipLevels(levels - 1);
            }
        }

        /**
         * Represents a single condition range, like #if - #endif, or #elif - #else.
         */
        static class Range {
            final Ifdef parent;
            final int index, start;
            final List<Ifdef> ifdef = new ArrayList<>();
            final String text;
            int end;
            Range(Ifdef parent, int start, String text) {
                this.parent = parent;
                this.start = start;
                this.text = text;
                index = parent.ranges.size();
                parent.ranges.add(this);
            }

            /**
             * Go to given position in source code, inserting encountered statements into given StringBuilder
             * @param content where statements are appended
             * @param index target position in source code (in characters)
             * @return new range which contains given position
             */
            Range goTo(StringBuilder content, int index) {
                if (index >= start && index < end) {
                    for (Ifdef d : ifdef) {
                        if (index >= d.start && index < d.end) return d.goTo(content, index, 0);
                    }
                    return this;
                } else {
                    return parent.goTo(content, index, this.index + 1);
                }
            }
        }

        /**
         * Parse source code and build a tree from extracted conditions
         * @param orig original source code
         * @param skipLevels how many top-level ranges to skip (this is usually 1 or more, as we want to at least skip include guards)
         * @return root range of the generated tree structure
         */
        static Range buildTree(String orig, int skipLevels) {
            Range root = new Range(new Ifdef(null, 0), 0, null), head = root;
            root.parent.end = root.end = orig.length();
            Pattern pattern = Pattern.compile("# *(.+)");
            Matcher matcher = pattern.matcher(orig);
            while (matcher.find()) {
                String t = matcher.group(1);
                if (t.startsWith("if")) {
                    Ifdef d = new Ifdef(head, matcher.start());
                    head = new Range(d, matcher.start(), t);
                } else if (t.startsWith("elif") || t.startsWith("else")) {
                    head.parent.ranges.get(head.parent.ranges.size() - 1).end = matcher.start();
                    head = new Range(head.parent, matcher.start(), t);
                } else if (t.startsWith("endif")) {
                    head.parent.ranges.get(head.parent.ranges.size() - 1).end = matcher.start();
                    head.parent.end = matcher.start();
                    head = head.parent.parent;
                }
            }
            root.parent.skipLevels(skipLevels + 1);
            return root;
        }
    }

    record TemplateEntry<T>(T data, int position) {
        static final Pattern loopPattern = Pattern.compile("([ \\t]*)\\{\\{\\{([\\w\\W]+?)}}}\n");
        static final Pattern entryPattern = Pattern.compile("\\$\\{(.*?\\^)?(.*?)(\\$.*?)?}");
    }

    /**
     * Expressions $0 ... $N are replaced with corresponding strings from {@code replacementStrings}
     */
    static String processTemplate(String template, String... replacementStrings) {
        for (int i = 0; i < replacementStrings.length; i++) {
            String pattern = "$" + i;
            String[] replacementLines = replacementStrings[i].split("\n");
            for (int ix, last = template.length(); (ix = template.lastIndexOf(pattern, last)) != -1; last = ix) {
                int nl = template.lastIndexOf('\n', ix) + 1;
                String indent = "\n" + " ".repeat(ix - nl);
                StringBuilder s = new StringBuilder();
                s.append(template, 0, ix).append(replacementLines[0]);
                for (int j = 1; j < replacementLines.length; j++) s.append(indent).append(replacementLines[j]);
                s.append(template, ix + pattern.length(), template.length());
                template = s.toString();
            }
        }
        return template.replaceAll("(?m)^[ \\t]+(?=$|#)", ""); // Strip blank lines & trailing whitespaces for preprocessor defs
    }

    /**
     * Expressions inside triple curly braces {{{}}} are replaced for each entry in {@code entries} list,
     * where you can use ${someGetter} expressions to access getter methods of the entry
     * and {@code ${firstIteration^anything$lastIteration}} syntax to add text only for specific iterations
     * (e.g. when generating comma separated lists).
     * Then expressions $0 ... $N are replaced with corresponding strings from {@code replacementStrings}
     */
    static <T> String processTemplate(Ifdef.Range ifdef, int sourcePosition,
                                      String template, List<TemplateEntry<T>> entries, String... replacementStrings) {
        int lastIndex = 0;
        StringBuilder content = new StringBuilder();
        ifdef = ifdef.goTo(content, sourcePosition);
        Matcher loopMatcher = TemplateEntry.loopPattern.matcher(template);
        while (loopMatcher.find()) {
            content.append(template, lastIndex, loopMatcher.start());
            lastIndex = loopMatcher.end();
            String indent = loopMatcher.group(1);
            String entryTemplate = loopMatcher.group(2);
            Matcher entryMatcher = TemplateEntry.entryPattern.matcher(entryTemplate);
            List<IntFunction<String>> entryTextParts = new ArrayList<>();
            int li = 0;
            while (entryMatcher.find()) {
                String textPart = entryTemplate.substring(li, entryMatcher.start());
                entryTextParts.add(i -> textPart);
                li = entryMatcher.end();
                String firstIteration = entryMatcher.group(1), entry = entryMatcher.group(2), lastIteration = entryMatcher.group(3);
                if (firstIteration == null && lastIteration == null) {
                    try {
                        Method getter = entries.get(0).data.getClass().getMethod(entry);
                        entryTextParts.add(i -> {
                            try {
                                return getter.invoke(entries.get(i).data).toString();
                            } catch (IllegalAccessException | InvocationTargetException e) {
                                throw new Error(e);
                            }
                        });
                    } catch (NoSuchMethodException e) {
                        throw new Error(e);
                    }
                } else {
                    entryTextParts.add(i -> {
                        if (firstIteration != null && i == 0) return firstIteration.substring(0, firstIteration.length() - 1);
                        if (lastIteration != null && i == entries.size() - 1) return lastIteration.substring(1);
                        return entry;
                    });
                }
            }
            String lastPart = entryTemplate.substring(li);
            entryTextParts.add(i -> lastPart);
            for (int i = 0; i < entries.size(); i++) {
                ifdef = ifdef.goTo(content, entries.get(i).position);
                if (i != 0) content.append("\n");
                content.append(indent);
                for (var e : entryTextParts) content.append(e.apply(i));
            }
            ifdef = ifdef.goTo(content, sourcePosition);
            content.append("\n");
        }
        content.append(template.substring(lastIndex));
        ifdef.goTo(content, -1);
        return processTemplate(content.toString(), replacementStrings);
    }

    static void generateEnums(String orig, Ifdef.Range ifdef) throws IOException {
        record Entry(String name, String originalName) {}
        StringBuilder content = new StringBuilder(), vkContent = new StringBuilder();
        Pattern typedefPattern = Pattern.compile("typedef\\s+enum\\s+Vma(\\w+)");
        Pattern entryPattern = Pattern.compile("(VMA_\\w+)[^,}]*");
        Matcher typedefMatcher = typedefPattern.matcher(orig);
        while (typedefMatcher.find()) {
            String name = typedefMatcher.group(1);
            boolean flagBits = name.endsWith("FlagBits");
            String flags = flagBits ? name.substring(0, name.length() - 4) + "s" : null;

            String body = orig.substring(typedefMatcher.end(), orig.indexOf("}", typedefMatcher.end()));
            Matcher entryMatcher = entryPattern.matcher(body);
            List<TemplateEntry<Entry>> entries = new ArrayList<>();
            while (entryMatcher.find()) {
                String value = entryMatcher.group(1);
                if (value.endsWith("_MAX_ENUM")) break;
                if (flagBits && !value.endsWith("_BIT")) continue;
                String entry = Stream.of(value.split("_"))
                        .map(t -> t.substring(0, 1).toUpperCase() + t.substring(1).toLowerCase())
                        .collect(Collectors.joining());
                if (flagBits) entry = entry.substring(name.length() - 5, entry.length() - 3);
                else entry = entry.substring(name.length() + 3);

                entries.add(new TemplateEntry<>(new Entry(entry, value), typedefMatcher.end() + entryMatcher.start()));
            }

            content.append(processTemplate(ifdef, typedefMatcher.start(), """
                    
                    enum class $0$1 {
                      {{{e${name} = ${originalName}${,$}}}}
                    };
                    
                    VULKAN_HPP_INLINE std::string to_string($0 value) {
                      {{{if (value == $0::e${name}) return "${name}";}}}
                      return "invalid ( " + VULKAN_HPP_NAMESPACE::toHexString(static_cast<uint32_t>(value)) + " )";
                    }
                    """, entries, name, flagBits ? (" : Vma" + flags) : ""));
            if (flagBits) {
                content.append(processTemplate(ifdef, typedefMatcher.start(), """
                        
                        using $1 = VULKAN_HPP_NAMESPACE::Flags<$0>;
                                            
                        VULKAN_HPP_INLINE VULKAN_HPP_CONSTEXPR $1 operator|($0 bit0, $0 bit1) VULKAN_HPP_NOEXCEPT {
                          return $1(bit0) | bit1;
                        }
                        
                        VULKAN_HPP_INLINE VULKAN_HPP_CONSTEXPR $1 operator&($0 bit0, $0 bit1) VULKAN_HPP_NOEXCEPT {
                          return $1(bit0) & bit1;
                        }
                        
                        VULKAN_HPP_INLINE VULKAN_HPP_CONSTEXPR $1 operator^($0 bit0, $0 bit1) VULKAN_HPP_NOEXCEPT {
                          return $1(bit0) ^ bit1;
                        }
                        
                        VULKAN_HPP_INLINE VULKAN_HPP_CONSTEXPR $1 operator~($0 bits) VULKAN_HPP_NOEXCEPT {
                          return ~($1(bits));
                        }
                        
                        VULKAN_HPP_INLINE std::string to_string($1 value) {
                          if (!value) return "{}";
                          std::string result;
                          {{{if (value & $0::e${name}) result += "${name} | ";}}}
                          return "{ " + result.substr( 0, result.size() - 3 ) + " }";
                        }
                        """, entries, name, flags));
                vkContent.append(processTemplate(ifdef, typedefMatcher.start(), """
                    
                    template<> struct FlagTraits<VMA_HPP_NAMESPACE::$0> {
                      enum : VkFlags {
                        allFlags =
                          {{{${ ^|} VkFlags(VMA_HPP_NAMESPACE::$0::e${name})}}}
                      };
                    };
                    """, entries, name));
            }
        }
        Files.writeString(Path.of("vk_mem_alloc_enums.hpp"), processTemplate("""
                #ifndef VULKAN_MEMORY_ALLOCATOR_ENUMS_HPP
                #define VULKAN_MEMORY_ALLOCATOR_ENUMS_HPP

                namespace VMA_HPP_NAMESPACE {
                  $0
                }

                namespace VULKAN_HPP_NAMESPACE {
                  $1
                }
                #endif
                """, content.toString(), vkContent.toString()));
    }

    enum VarTag {
        NONE,
        NULLABLE,
        NOT_NULL
    }

    /**
     * Parsed variable declaration (field or function parameter)
     * @param type (probably) converted type, e.g. VkResult -> vk::Result or VmaAllocator -> vma::Allocator
     * @param name variable name
     * @param constant whether it's constant
     * @param pointer whether it's a pointer
     * @param originalType original type
     */
    record Var(String originalType, boolean constant, String type, boolean pointer, VarTag tag, String lenIfNotNull, boolean primitive, String name) {

        static final Pattern pattern = Pattern.compile(
                "(const\\s+)?(\\w+)" +
                "(\\s*\\*)?(?:\\s+VMA_(NULLABLE|NOT_NULL)(?:_NON_DISPATCHABLE)?)?" +
                "(\\s*\\*)?(?:\\s+VMA_(NULLABLE|NOT_NULL)(?:_NON_DISPATCHABLE)?)?" +
                "(?:\\s+VMA_LEN_IF_NOT_NULL\\(\\s*([^)]+)\\s*\\))?" +
                "\\s+(\\w+)" +
                "((?:\\s*\\[\\w+])+)?" +
                "\\s*[;,)]");
        private static String applyConstAndPointers(String type, boolean constant, boolean pointer1, boolean pointer2) {
            if (constant) type = "const " + type;
            if (pointer1) type += "*";
            if (pointer2) type += "*";
            return type;
        }
        static Var parse(Matcher matcher) {
            String originalType = matcher.group(2), type = originalType;

            if (type.startsWith("Vk")) type = "VULKAN_HPP_NAMESPACE::" + type.substring(2);
            else if (type.startsWith("Vma")) type = type.substring(3);

            boolean primitive = switch (type) {
                case "void", "char", "uint32_t", "size_t" -> true;
                default -> false;
            };

            boolean c = matcher.group(1) != null, p1 = matcher.group(3) != null, p2 = matcher.group(5) != null;
            type = applyConstAndPointers(type, c, p1, p2);
            originalType = applyConstAndPointers(originalType, c, p1, p2);

            String tag1 = matcher.group(4), tag2 = matcher.group(6);
            VarTag tag = VarTag.NONE;
            if (p2) {
                if (tag2 != null) tag = VarTag.valueOf(tag2);
            }  else if (tag1 != null) tag = VarTag.valueOf(tag1);

            String arr = matcher.group(9);
            if (arr != null) {
                arr = arr.strip();
                while (!arr.isEmpty()) {
                    int i = arr.lastIndexOf('[');
                    String dim = arr.substring(i + 1, arr.length() - 1).strip();
                    arr = arr.substring(0, i).strip();
                    type = "std::array<" + type + ", " + dim + ">";
                }
            }

            if (p1 && p2) c = false; // double pointer, then first-level pointer is not const

            return new Var(originalType, c, type, p1 || p2, tag, matcher.group(7), primitive, matcher.group(8));
        }

        public String capitalName() {
            return name.substring(0, 1).toUpperCase() + name.substring(1);
        }

        private static final Pattern pointerVariableNamePattern = Pattern.compile("p+([A-Z])(\\w+)");
        public String prettyName() {
            Matcher m = pointerVariableNamePattern.matcher(name);
            return m.matches() ? m.group(1).toLowerCase() + m.group(2) : name;
        }

        public String stripPtr() {
            return type.substring(0, type.length() - 1);
        }
    }

    static List<String> generateStructs(String orig, Ifdef.Range ifdef) throws IOException {
        List<String> structs = new ArrayList<>();
        StringBuilder content = new StringBuilder();
        Pattern typedefPattern = Pattern.compile("typedef\\s+struct\\s+Vma(\\w+)");
        Matcher typedefMatcher = typedefPattern.matcher(orig);
        while (typedefMatcher.find()) {
            String name = typedefMatcher.group(1);
            structs.add(name);
            String body = orig.substring(typedefMatcher.end(), orig.indexOf("}", typedefMatcher.end()));
            List<TemplateEntry<Var>> fields = new ArrayList<>();
            Matcher memberMatcher = Var.pattern.matcher(body);
            while (memberMatcher.find()) {
                fields.add(new TemplateEntry<>(Var.parse(memberMatcher),  typedefMatcher.end() + memberMatcher.start()));
            }
            content.append(processTemplate(ifdef, typedefMatcher.start(), """
                    
                    struct $0 {
                      using NativeType = Vma$0;
                    
                    #if !defined( VULKAN_HPP_NO_STRUCT_CONSTRUCTORS )
                      VULKAN_HPP_CONSTEXPR $0(
                          {{{${ ^,} ${type} ${name}_ = {}}}}
                        ) VULKAN_HPP_NOEXCEPT
                        {{{${:^,} ${name}(${name}_)}}}
                        {}
                    #endif
                    
                      $0& operator=($0 const &) VULKAN_HPP_NOEXCEPT = default;
                      $0& operator=(Vma$0 const & rhs) VULKAN_HPP_NOEXCEPT {
                        *this = *reinterpret_cast<VMA_HPP_NAMESPACE::$0 const *>(&rhs);
                        return *this;
                      }
                      
                      explicit operator Vma$0 const &() const VULKAN_HPP_NOEXCEPT {
                        return *reinterpret_cast<const Vma$0 *>(this);
                      }
                      
                      explicit operator Vma$0&() VULKAN_HPP_NOEXCEPT {
                        return *reinterpret_cast<Vma$0 *>(this);
                      }
                      
                    #if defined( VULKAN_HPP_HAS_SPACESHIP_OPERATOR )
                      auto operator<=>($0 const &) const = default;
                    #else
                      bool operator==($0 const &) const = default;
                    #endif
                    
                    #if !defined( VULKAN_HPP_NO_STRUCT_SETTERS )
                    {{{
                      VULKAN_HPP_CONSTEXPR_14 $0& set${capitalName}(${type} ${name}_) VULKAN_HPP_NOEXCEPT {
                        ${name} = ${name}_;
                        return *this;
                      }}}}
                    #endif
                    
                    public:
                      {{{${type} ${name} = {};}}}
                    };
                    VULKAN_HPP_STATIC_ASSERT(sizeof($0) == sizeof(Vma$0),
                                             "struct and wrapper have different size!");
                    VULKAN_HPP_STATIC_ASSERT(std::is_standard_layout<$0>::value,
                                             "struct wrapper is not a standard layout!");
                    VULKAN_HPP_STATIC_ASSERT(std::is_nothrow_move_constructible<$0>::value,
                                             "$0 is not nothrow_move_constructible!");
                    """, fields, name));
        }
        Files.writeString(Path.of("vk_mem_alloc_structs.hpp"), processTemplate("""
                #ifndef VULKAN_MEMORY_ALLOCATOR_STRUCTS_HPP
                #define VULKAN_MEMORY_ALLOCATOR_STRUCTS_HPP

                namespace VMA_HPP_NAMESPACE {
                  $0
                }
                #endif
                """, content.toString()));
        return structs;
    }

    /**
     * Handle class, from which ..._handles.hpp and ..._funcs.hpp files are generated
     */
    static class Handle {
        final String name;
        final boolean dispatchable;
        final StringBuilder declarations = new StringBuilder(), definitions = new StringBuilder();
        Ifdef.Range ifdef;

        Handle(String name, boolean dispatchable, Ifdef.Range ifdef) {
            this.name = name;
            this.dispatchable = dispatchable;
            this.ifdef = ifdef;
        }

        String getLowerName() {
            return name.substring(0, 1).toLowerCase() + name.substring(1);
        }

        String generateClass() {
            return processTemplate("""
                    class $0 {
                    public:
                      using CType      = Vma$0;
                      using NativeType = Vma$0;
                    public:
                      VULKAN_HPP_CONSTEXPR         $0() = default;
                      VULKAN_HPP_CONSTEXPR         $0(std::nullptr_t) VULKAN_HPP_NOEXCEPT {}
                      VULKAN_HPP_TYPESAFE_EXPLICIT $0(Vma$0 $1) VULKAN_HPP_NOEXCEPT : m_$1($1) {}
                    
                    #if defined(VULKAN_HPP_TYPESAFE_CONVERSION)
                      $0& operator=(Vma$0 $1) VULKAN_HPP_NOEXCEPT {
                        m_$1 = $1;
                        return *this;
                      }
                    #endif
                    
                      $0& operator=(std::nullptr_t) VULKAN_HPP_NOEXCEPT {
                        m_$1 = {};
                        return *this;
                      }
                    
                    #if defined( VULKAN_HPP_HAS_SPACESHIP_OPERATOR )
                      auto operator<=>($0 const &) const = default;
                    #else
                      bool operator==($0 const & rhs) const VULKAN_HPP_NOEXCEPT {
                        return m_$1 == rhs.m_$1;
                      }
                    #endif
                    
                      VULKAN_HPP_TYPESAFE_EXPLICIT operator Vma$0() const VULKAN_HPP_NOEXCEPT {
                        return m_$1;
                      }
                    
                      explicit operator bool() const VULKAN_HPP_NOEXCEPT {
                        return m_$1 != VK_NULL_HANDLE;
                      }
                    
                      bool operator!() const VULKAN_HPP_NOEXCEPT {
                        return m_$1 == VK_NULL_HANDLE;
                      }
                    $2
                    private:
                      Vma$0 m_$1;
                    };
                    VULKAN_HPP_STATIC_ASSERT(sizeof($0) == sizeof(Vma$0),
                                             "handle and wrapper have different size!");
                    """, name, getLowerName(), declarations.toString().indent(2));
        }
    }

    static String deduceVectorSize(String funcName, String lenIfNotNull) {
        // These are exceptional cases, which needs some custom code to achieve smart behavior
        if (funcName.equals("vmaGetHeapBudgets") && lenIfNotNull.equals("\"VkPhysicalDeviceMemoryProperties::memoryHeapCount\"")) {
            return "getMemoryProperties()->memoryHeapCount";
        } else {
            throw new Error("Don't know how to deduce vector size: " + lenIfNotNull + " in " + funcName);
        }
    }

    static void generateHandles(String orig, Ifdef.Range ifdef, List<String> structs) throws IOException {
        // Forward declarations for structs
        StringBuilder declarations = new StringBuilder(), definitions = new StringBuilder();
        for (String s : structs) declarations.append("\nstruct ").append(s).append(";");

        // Find all handles
        Map<String, Handle> handles = new HashMap<>();
        Pattern handlePattern = Pattern.compile("VK_DEFINE_(NON_DISPATCHABLE_)?HANDLE\\s*\\(\\s*Vma(\\w+)\\s*\\)");
        Matcher handleMatcher = handlePattern.matcher(orig);
        while (handleMatcher.find()) {
            String name = handleMatcher.group(2);
            Handle h = new Handle(name, handleMatcher.group(1) == null, ifdef);
            handles.put("Vma" + name, h);
        }

        // Forward declarations for handles
        declarations.append("\n\n");
        for (Handle h : handles.values()) declarations.append("class ").append(h.name).append(";\n");

        // Iterate VMA functions
        Pattern funcPattern = Pattern.compile("VMA_CALL_PRE\\s+(\\w+)\\s+VMA_CALL_POST\\s+vma(\\w+)\\s*(\\([\\s\\S]+?\\)\\s*;)");
        Matcher funcMatcher = funcPattern.matcher(orig);
        while (funcMatcher.find()) {
            Matcher paramMatcher = Var.pattern.matcher(funcMatcher.group(3));
            List<Var> params = new ArrayList<>();
            while (paramMatcher.find()) params.add(Var.parse(paramMatcher));
            // Find dispatchable handle if any
            Handle handle = handles.get(params.get(0).originalType);
            if (handle != null) params.remove(0);

            Map<String, Integer> paramIndexByName = new HashMap<>();
            for (int i = 0; i < params.size(); i++) paramIndexByName.put(params.get(i).name, i);

            String name = funcMatcher.group(2);
            String funcName = "vma" + name; // Original function name
            if (handle != null && name.equals("Destroy" + handle.name)) name = "destroy"; // E.g. Allocator::destroyAllocator -> Allocator::destroy
            String methodName = name.substring(0, 1).toLowerCase() + name.substring(1); // Generated method name

            // Find dependencies of array sizes
            Integer[] arrayByLengthIndex = new Integer[params.size()];
            for (int i = 0; i < params.size(); i++) {
                Var v = params.get(i);
                if (v.lenIfNotNull == null) continue;
                if (v.constant) { // Input array, respective size parameter can be deduced
                    Integer l = paramIndexByName.get(v.lenIfNotNull);
                    if (l != null && arrayByLengthIndex[l] == null) arrayByLengthIndex[l] = i;
                }
            }

            // Find output params
            List<Integer> outputs = new ArrayList<>(), defaultedOutputs = new ArrayList<>();
            for (int i = 0; i < params.size(); i++) {
                Var v = params.get(i);
                if (v.pointer && !v.constant) {
                    if (v.tag == VarTag.NOT_NULL) outputs.add(i);
                    else if (!v.primitive) {
                        defaultedOutputs.add(i);
                        continue;
                    }
                }
                defaultedOutputs.clear();
            }

            String returnType = switch (funcMatcher.group(1)) {
                case "void" -> "void";
                case "VkResult" -> "VULKAN_HPP_NAMESPACE::Result";
                case "VkBool32" -> "VULKAN_HPP_NAMESPACE::Bool32";
                default -> throw new Error("Unknown return type: " + funcMatcher.group(1));
            };

            class Method {
                final boolean enhanced;
                final List<String> paramTypes = new ArrayList<>(), paramsPass = new ArrayList<>();
                final List<Integer> paramIndices = new ArrayList<>();

                Method(boolean enhanced) {
                    this.enhanced = enhanced;
                    if (handle != null) paramsPass.add("m_" + handle.getLowerName());
                    for (int i = 0; i < params.size(); i++) {
                        Var p = params.get(i);

                        String v = p.prettyName();
                        if (p.type.equals(p.originalType)) {
                            if (enhanced && outputs.contains(i)) v = "&" + v;
                        } else if (p.pointer) {
                            if (enhanced) {
                                if (p.lenIfNotNull != null) v += ".data()";
                                else if (p.tag == VarTag.NOT_NULL) v = "&" + v;
                                else if (!p.constant && !p.primitive) v = "static_cast<" + p.type + ">(" + v + ")";
                            }
                            v = "reinterpret_cast<" + p.originalType + ">(" + v + ")";
                        } else v = "static_cast<" + p.originalType + ">(" + v + ")";
                        paramsPass.add(v);

                        if (enhanced) {
                            if (outputs.contains(i)) continue; // Skip output parameters
                            if (arrayByLengthIndex[i] != null) continue; // Skip length parameters which can be deduced
                        }

                        String t = p.type;
                        if (enhanced && p.pointer) {
                            if (p.lenIfNotNull != null) t = "VULKAN_HPP_NAMESPACE::" + (p.constant ? "ArrayProxy<" : "ArrayProxyNoTemporaries<") + p.stripPtr() + ">";
                            else if (p.tag == VarTag.NOT_NULL) t = p.stripPtr() + "&";
                            else if (!p.constant && !p.primitive) t = "VULKAN_HPP_NAMESPACE::Optional<" + p.stripPtr() + ">";
                        }
                        paramTypes.add(t);
                        paramIndices.add(i);
                    }
                }

                String generate(boolean definition, boolean customVectorAllocator) {
                    if (outputs.size() >= 3) throw new Error("3+ mandatory outputs");
                    if (outputs.size() != 0 && !returnType.equals("void") && !returnType.equals("VULKAN_HPP_NAMESPACE::Result")) throw new Error("Both return value and output parameters");
                    if (outputs.size() >= 2 && params.get(outputs.get(0)).lenIfNotNull != null) throw new Error("2+ mandatory outputs with at least one array");
                    String ret = enhanced ? switch (outputs.size()) {
                        case 2 -> "std::pair<" + params.get(outputs.get(0)).stripPtr() + ", " + params.get(outputs.get(1)).stripPtr() + ">";
                        case 1 -> params.get(outputs.get(0)).lenIfNotNull != null ? "std::vector<" + params.get(outputs.get(0)).stripPtr() + ", VectorAllocator>" : params.get(outputs.get(0)).stripPtr();
                        default -> returnType.equals("VULKAN_HPP_NAMESPACE::Result") ? "void" : returnType;
                    } : returnType;

                    String decl = "";
                    // Generate template for vector allocator
                    if (enhanced && outputs.size() == 1 && params.get(outputs.get(0)).lenIfNotNull != null) {
                        if (!customVectorAllocator) decl = generate(definition, true) + "\n";
                        decl += "template<typename VectorAllocator";
                        if (!definition) decl += " = std::allocator<" + params.get(outputs.get(0)).stripPtr() + ">";
                        if (customVectorAllocator) {
                            decl += ",\n         typename B";
                            if (!definition) decl += " = VectorAllocator";
                            decl += ",\n         typename std::enable_if<std::is_same<typename B::value_type, " +
                                    params.get(outputs.get(0)).stripPtr() + ">::value, int>::type";
                            if (!definition) decl += " = 0";
                        }
                        decl += ">\n";
                    }

                    if (definition) decl += "VULKAN_HPP_INLINE ";
                    else if (!ret.equals("void")) decl += enhanced ? "VULKAN_HPP_NODISCARD_WHEN_NO_EXCEPTIONS " : "VULKAN_HPP_NODISCARD ";
                    decl += (enhanced && returnType.equals("VULKAN_HPP_NAMESPACE::Result") ? "typename VULKAN_HPP_NAMESPACE::ResultValueType<" + ret + ">::type" : ret) + " ";
                    if (definition && handle != null) decl += handle.name + "::";
                    StringBuilder s = new StringBuilder();
                    for (int i = 0; i < paramTypes.size(); i++) {
                        if (i != 0) s.append(",\n");
                        s.append(paramTypes.get(i)).append(" ").append(params.get(paramIndices.get(i)).prettyName());
                        if (enhanced && !definition && defaultedOutputs.contains(paramIndices.get(i)) && !customVectorAllocator) s.append(" = nullptr");
                    }
                    if (customVectorAllocator) {
                        if (!paramTypes.isEmpty()) s.append(",\n");
                        s.append("VectorAllocator& vectorAllocator");
                    }
                    decl = processTemplate("$0$1($2)$3", decl, methodName, s.toString(), handle != null ? " const" : "");
                    if (!definition) return decl + ";\n";

                    s.setLength(0);
                    if (enhanced) {
                        // Generate deduction statements
                        for (int i = 0; i < params.size(); i++) {
                            Var p = params.get(i);
                            if (arrayByLengthIndex[i] != null) s.append(p.type).append(" ").append(p.prettyName())
                                    .append(" = ").append(params.get(arrayByLengthIndex[i]).prettyName()).append(".size();\n");
                        }
                        // Generate output variable declarations
                        if (outputs.size() == 2) {
                            Var p1 = params.get(outputs.get(0)), p2 = params.get(outputs.get(1));
                            s.append(ret).append(" pair;\n")
                                    .append(p1.stripPtr()).append("& ").append(p1.prettyName()).append(" = pair.first;\n")
                                    .append(p2.stripPtr()).append("& ").append(p2.prettyName()).append(" = pair.second;\n");
                        } else if (outputs.size() == 1) {
                            Var p = params.get(outputs.get(0));
                            s.append(ret).append(" ").append(p.prettyName());
                            if (p.lenIfNotNull != null) {
                                s.append("(").append(paramIndexByName.get(p.lenIfNotNull) != null ?
                                        p.lenIfNotNull : deduceVectorSize(funcName, p.lenIfNotNull)).append(customVectorAllocator ? ", vectorAllocator)" : ")");
                            }
                            s.append(";\n");
                        }
                    }
                    // Generate call
                    if (!returnType.equals("void")) s.append(returnType).append(" result = static_cast<").append(returnType).append(">( ");
                    s.append(funcName).append("(").append(String.join(", ", paramsPass)).append(")");
                    if (!returnType.equals("void")) s.append(" )");
                    s.append(";");
                    // Generate return statement
                    String returnValue = enhanced ? switch (outputs.size()) {
                        case 2 -> "pair";
                        case 1 -> params.get(outputs.get(0)).prettyName();
                        default -> "result";
                    } :  "result";
                    if (enhanced && returnType.equals("VULKAN_HPP_NAMESPACE::Result")) { // Check result
                        if (ret.equals("void")) returnValue = "result";
                        else returnValue = "result, " + returnValue;
                        s.append("\nreturn createResultValue(").append(returnValue).append(", VMA_HPP_NAMESPACE_STRING \"::");
                        if (handle != null) s.append(handle.name).append("::");
                        s.append(methodName).append("\");");
                    } else if (!ret.equals("void")) s.append("\nreturn ").append(returnValue).append(";");
                    return processTemplate("""
                                $0 {
                                  $1
                                }
                                """, decl, s.toString());
                }
            }
            Method simple = new Method(false), enhanced = new Method(true);

            StringBuilder defs = handle != null ? handle.definitions : definitions, decls = handle != null ? handle.declarations : declarations;
            boolean sameSignatures = simple.paramTypes.equals(enhanced.paramTypes);

            (handle != null ? handle.ifdef : ifdef).goTo(decls, funcMatcher.start());
            if (handle != null) handle.ifdef = handle.ifdef.goTo(defs, funcMatcher.start());
            else ifdef = ifdef.goTo(defs, funcMatcher.start());

            decls.append("\n#ifndef VULKAN_HPP_DISABLE_ENHANCED_MODE\n").append(enhanced.generate(false, false));
            defs.append("\n#ifndef VULKAN_HPP_DISABLE_ENHANCED_MODE\n").append(enhanced.generate(true, false));
            decls.append(sameSignatures ? "#else\n" : "#endif\n").append(simple.generate(false, false));
            defs.append(sameSignatures ? "#else\n" : "#endif\n").append(simple.generate(true, false));
            if (sameSignatures) {
                decls.append("#endif\n");
                defs.append("#endif\n");
            }
        }

        for (Handle h : handles.values()) {
            h.ifdef.goTo(h.declarations, -1);
            h.ifdef.goTo(h.definitions, -1);
            declarations.append("\n").append(h.generateClass());
            definitions.append(h.definitions);
        }
        ifdef.goTo(declarations, -1);
        ifdef.goTo(definitions, -1);

        Files.writeString(Path.of("vk_mem_alloc_handles.hpp"), processTemplate("""
                #ifndef VULKAN_MEMORY_ALLOCATOR_HANDLES_HPP
                #define VULKAN_MEMORY_ALLOCATOR_HANDLES_HPP

                namespace VMA_HPP_NAMESPACE {
                  $0
                }
                #endif
                """, declarations.toString()));

        Files.writeString(Path.of("vk_mem_alloc_funcs.hpp"), processTemplate("""
                #ifndef VULKAN_MEMORY_ALLOCATOR_FUNCS_HPP
                #define VULKAN_MEMORY_ALLOCATOR_FUNCS_HPP

                namespace VMA_HPP_NAMESPACE {
                  $0
                }
                #endif
                """, definitions.toString()));
    }

    public static void main(String[] args) throws Exception {
        String orig = Files.readString(Path.of("vk_mem_alloc.h"))
                .replaceAll("/\\*[\\s\\S]*?\\*/", "") // Delete multi-line comments
                .replaceAll("//.*", ""); // Delete single-line comments
        orig = orig.substring(0, orig.indexOf("#ifdef VMA_IMPLEMENTATION")); // Strip implementation part

        Ifdef.Range ifdef = Ifdef.buildTree(orig, 2);

        generateEnums(orig, ifdef);
        List<String> structs = generateStructs(orig, ifdef);
        generateHandles(orig, ifdef, structs);
    }

}
