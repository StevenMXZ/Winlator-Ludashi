#include "shader.hpp"
#include "egl.hpp"

Shader::Shader(const char *vertexShader, const char *fragmentShader) {
    programId = glCreateProgram();
        
    int vertexShaderId = glCreateShader(GL_VERTEX_SHADER);
    glShaderSource(vertexShaderId, 1, &vertexShader, nullptr);
    glCompileShader(vertexShaderId);
    glAttachShader(programId, vertexShaderId);
    
    int fragmentShaderId = glCreateShader(GL_FRAGMENT_SHADER);
    glShaderSource(fragmentShaderId, 1, &fragmentShader, nullptr);
    glCompileShader(fragmentShaderId);
    glAttachShader(programId, fragmentShaderId);
    
    glLinkProgram(programId);
    
    glDeleteShader(vertexShaderId);
    glDeleteShader(fragmentShaderId);
    
    glGenBuffers(1, &bufferId);
    glBindBuffer(GL_ARRAY_BUFFER, bufferId);
    glBufferData(GL_ARRAY_BUFFER, sizeof(quads), quads, GL_STATIC_DRAW);
    glBindBuffer(GL_ARRAY_BUFFER, 0);
}

Shader::~Shader() {
    glDeleteProgram(programId);
    glDeleteBuffers(1, &bufferId);
}

void Shader::use() {
    glUseProgram(programId);
    
    glBindBuffer(GL_ARRAY_BUFFER, bufferId);
    
    int position = attributeLocations["position"];
        
    glEnableVertexAttribArray(position);
    glVertexAttribPointer(position, 2, GL_FLOAT, false, 0, 0);
}

void Shader::disable() {
    glDisableVertexAttribArray(attributeLocations["position"]);
    glBindBuffer(GL_ARRAY_BUFFER, 0);
}

int Shader::getAttributeLoc(const std::string& name) {
    return attributeLocations[name];
}

int Shader::getUniformLoc(const std::string& name) {
    return uniformLocations[name];
}

DrawableShader::DrawableShader() : Shader(drawable_vert, drawable_frag) {
    attributeLocations["position"] = glGetAttribLocation(programId, "position");
    uniformLocations["viewSize"] = glGetUniformLocation(programId, "viewSize");
    uniformLocations["texture"] = glGetUniformLocation(programId, "texture");
    uniformLocations["xform"] = glGetUniformLocation(programId, "xform");
    uniformLocations["is_cursor"] = glGetUniformLocation(programId, "is_cursor");
    uniformLocations["swap_colors"] = glGetUniformLocation(programId, "swap_colors");
}