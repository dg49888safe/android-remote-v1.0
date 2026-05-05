# Dockerfile for Node.js Backend Server

# Base image
FROM node:14

# Set working directory
WORKDIR /usr/src/app

# Copy package.json and package-lock.json
COPY package*.json ./

# Install dependencies
RUN npm install

# Copy all application files
COPY . .

# Expose port
EXPOSE 3000

# Command to run the application
CMD [ "npm", "start" ]
