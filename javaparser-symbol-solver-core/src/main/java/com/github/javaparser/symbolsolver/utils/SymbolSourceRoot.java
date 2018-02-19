/*
 * Copyright (C) 2007-2010 Júlio Vilmar Gesser.
 * Copyright (C) 2011, 2013-2018 The JavaParser Team.
 *
 * This file is part of JavaParser.
 *
 * JavaParser can be used either under the terms of
 * a) the GNU Lesser General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 * b) the terms of the Apache License
 *
 * You should have received a copy of both licenses in LICENCE.LGPL and
 * LICENCE.APACHE. Please refer to those files for details.
 *
 * JavaParser is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 */

package com.github.javaparser.symbolsolver.utils;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseProblemException;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.symbolsolver.javaparsermodel.JavaParserFacade;
import com.github.javaparser.symbolsolver.model.resolution.TypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JarTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import com.github.javaparser.utils.Log;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Level;

import static com.github.javaparser.utils.Utils.assertNotNull;
import static java.nio.file.FileVisitResult.CONTINUE;
import static java.nio.file.FileVisitResult.SKIP_SIBLINGS;

public class SymbolSourceRoot {

    private static java.util.logging.Logger logger = java.util.logging.Logger.getLogger(JavaParserFacade.class.getCanonicalName());

    private final Path root;
    private CombinedTypeSolver typeSolver = new CombinedTypeSolver(new ReflectionTypeSolver(false));

    public SymbolSourceRoot(Path root) {
        assertNotNull(root);
        if (!Files.isDirectory(root)) {
            throw new IllegalArgumentException("Only directories are allowed as root path!");
        }
        this.root = root.normalize();
        Log.info("New symbol source root at \"%s\"", this.root);
    }

    public TypeSolver walk() throws IOException {
        Files.walkFileTree(root, new JavaSymbolSolverWalker());
        Files.walkFileTree(root, new JarVisitor());
        return typeSolver;
    }

    public Optional<TypeSolver> tryToWalk() {
        try {
            Files.walkFileTree(root, new JavaSymbolSolverWalker());
            Files.walkFileTree(root, new JarVisitor());
            return Optional.of(typeSolver);
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    public TypeSolver getTypeSolver() {
        return typeSolver;
    }

    /**
     * The path that was passed in the constructor.
     */
    public Path getRoot() {
        return root;
    }

    /**
     * Walks the directory and adds the roots of the java files to the TypeSolver
     */
    private class JavaSymbolSolverWalker extends SimpleFileVisitor<Path> {

        private final Set<Path> roots = new HashSet<>();

        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attr) throws FileNotFoundException {
            if (attr.isRegularFile()) {
                PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:**.java");
                if (matcher.matches(file)) {
                    try {
                        Optional<Path> root = JavaParser.parse(file.toFile()).getStorage()
                                .map(CompilationUnit.Storage::getSourceRoot);
                        if (root.isPresent()) {
                            typeSolver.add(new JavaParserTypeSolver(root.get().toFile()));
                            roots.add(root.get());
                            logger.log(Level.FINE, "Added dir " + root.get() + " to the TypeSolver");
                            return SKIP_SIBLINGS;
                        }
                    } catch (ParseProblemException e) {
                        logger.log(Level.WARNING, "Unable to parse file " + file, e);
                    }
                }
            }
            return CONTINUE;
        }

        @Override
        public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
            if (Files.isHidden(dir) || roots.stream().anyMatch(dir::startsWith)) {
                return SKIP_SIBLINGS;
            }
            return CONTINUE;
        }
    }

    private class JarVisitor extends SimpleFileVisitor<Path> {

        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attr) {
            if (attr.isRegularFile()) {
                PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:**.jar");
                if (matcher.matches(file)) {
                    try {
                        typeSolver.add(new JarTypeSolver(file.toString()));
                    } catch (IOException e) {
                        logger.log(Level.WARNING, "IOException for file " + file, e);
                    }
                }
            }
            return CONTINUE;
        }
    }
}

