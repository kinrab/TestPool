/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/JSP_Servlet/Servlet.java to edit this template
 */
package com.barnik.myservletproject;

import javax.naming.Context;
import javax.naming.InitialContext;
import javax.naming.NamingException;

import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.io.PrintWriter;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import java.util.Date;
import java.util.logging.Level;
import java.util.logging.Logger;

public class MyServlet extends HttpServlet 
{

    private static final Logger logger = Logger.getLogger(MyServlet.class.getName());
    private DataSource dataSource;

    @Override
    public void init() throws ServletException 
    {
        try 
        {
            // JNDI lookup выполняется один раз при старте
            Context initContext = new InitialContext();
            dataSource = (DataSource) initContext.lookup("java:comp/env/jdbc/postgres-pool");
        } 
        catch (NamingException e) 
        {
            logger.log(Level.SEVERE, "Critical error: DataSource 'jdbc/postgres-pool' not found", e);
            throw new ServletException("Can not configurate access to the database", e);
        }
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException 
    {
        response.setContentType("text/html;charset=UTF-8");
        PrintWriter out = response.getWriter();

        out.println("<html><head><title>User List</title></head><body>");
        out.println("<h1>Hi, Bro! This is servlet. I am running...</h1><br><br>");
        
        // Логика работы с БД вынесена в отдельный блок для чистоты
        renderUsersList(out);

        out.println("<br><br><p>Current time: " + new Date() + "</p>");
        out.println("</body></html>");
        
    }

    private void renderUsersList(PrintWriter out) 
    {
        String sql = "SELECT name FROM users_info;"; // Запрашиваем только то, что нужно

        // Автоматическое закрытие ресурсов: Connection -> Statement -> ResultSet
        try ( Connection conn = dataSource.getConnection(); PreparedStatement stmt = conn.prepareStatement(sql); ResultSet rs = stmt.executeQuery() ) 
        {
            out.println("<ul>");
            while (rs.next()) 
            {
                String name = rs.getString("name");
                out.println("<li>Name: " + (name != null ? name : "Anonymous") + "</li>");
            }
            out.println("</ul>");
            // Добавим sleep чтобы сервлет ждал 5 секунд имитирую захват коннекта:
            Thread.sleep(5000);

        }
        catch (InterruptedException e) 
        {
            // Это исключение для Thread.sleep
            Thread.currentThread().interrupt(); 
        }         
        catch (SQLException e) 
        {
            logger.log(Level.SEVERE, "SQL query execution error.", e);
            out.println("<p style='color:red;'>SQL query execution error.. Try again later.</p>");
        }
    }
    
} // End class
